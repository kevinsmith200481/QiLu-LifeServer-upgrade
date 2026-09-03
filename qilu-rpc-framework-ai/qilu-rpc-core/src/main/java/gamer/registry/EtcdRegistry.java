package gamer.registry;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.cron.CronUtil;
import cn.hutool.cron.task.Task;
import cn.hutool.json.JSONUtil;
import gamer.config.RegistryConfig;
import gamer.model.ServiceMetaInfo;
import io.etcd.jetcd.*;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchResponse;
import io.etcd.jetcd.watch.WatchEvent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EtcdRegistry implements Registry {

    private Client client;

    private KV kvClient;

    private final Set<String> localRegisterNodeKeySet = ConcurrentHashMap.newKeySet();

    private final RegistryServiceMultiCache registryServiceMultiCache = new RegistryServiceMultiCache();

    /** serviceKey -> 前缀 Watcher，computeIfAbsent 保证并发发现只创建一次。 */
    private final Map<String, Watch.Watcher> watchers = new ConcurrentHashMap<>();

    private static final String ETCD_ROOT_PATH = "/rpc/";

    @Override
    public void init(RegistryConfig registryConfig) {
        client = Client.builder()
                .endpoints(registryConfig.getAddress())
                .connectTimeout(Duration.ofMillis(registryConfig.getTimeout()))
                .build();
        kvClient = client.getKVClient();
        heartBeat();
    }

    @Override
    public void register(ServiceMetaInfo serviceMetaInfo) throws Exception {
        Lease leaseClient = client.getLeaseClient();

        long leaseId = leaseClient.grant(30).get().getID();

        String registerKey = ETCD_ROOT_PATH + serviceMetaInfo.getServiceNodeKey();
        ByteSequence key = ByteSequence.from(registerKey, StandardCharsets.UTF_8);
        ByteSequence value = ByteSequence.from(JSONUtil.toJsonStr(serviceMetaInfo), StandardCharsets.UTF_8);

        PutOption putOption = PutOption.builder().withLeaseId(leaseId).build();
        kvClient.put(key, value, putOption).get();
        localRegisterNodeKeySet.add(registerKey);
    }

    @Override
    public void unRegister(ServiceMetaInfo serviceMetaInfo) {
        String registerKey = ETCD_ROOT_PATH + serviceMetaInfo.getServiceNodeKey();
        kvClient.delete(ByteSequence.from(registerKey, StandardCharsets.UTF_8));
        localRegisterNodeKeySet.remove(registerKey);
    }

    @Override
    public List<ServiceMetaInfo> serviceDiscovery(String serviceKey) {
        List<ServiceMetaInfo> cachedServiceMetaInfoList = registryServiceMultiCache.readCache(serviceKey);
        if (cachedServiceMetaInfoList != null) {
            return cachedServiceMetaInfoList;
        }

        String searchPrefix = ETCD_ROOT_PATH + serviceKey + "/";

        try {
            // 先监听前缀再读取，消除“读取完成到注册监听之间节点变化”的窗口。
            watch(serviceKey);
            GetOption getOption = GetOption.builder().isPrefix(true).build();
            List<KeyValue> keyValues = kvClient.get(
                            ByteSequence.from(searchPrefix, StandardCharsets.UTF_8),
                            getOption)
                    .get()
                    .getKvs();
            List<ServiceMetaInfo> serviceMetaInfoList = keyValues.stream()
                    .map(keyValue -> {
                        String value = keyValue.getValue().toString(StandardCharsets.UTF_8);
                        return JSONUtil.toBean(value, ServiceMetaInfo.class);
                    })
                    .collect(Collectors.toList());
            registryServiceMultiCache.writeCache(serviceKey, serviceMetaInfoList);
            return serviceMetaInfoList;
        } catch (Exception e) {
            throw new RuntimeException("获取服务列表失败", e);
        }
    }

    @Override
    public void heartBeat() {
        CronUtil.schedule("*/10 * * * * *", new Task() {
            @Override
            public void execute() {
                for (String key : localRegisterNodeKeySet) {
                    try {
                        List<KeyValue> keyValues = kvClient.get(ByteSequence.from(key, StandardCharsets.UTF_8))
                                .get()
                                .getKvs();
                        if (CollUtil.isEmpty(keyValues)) {
                            continue;
                        }
                        KeyValue keyValue = keyValues.get(0);
                        String value = keyValue.getValue().toString(StandardCharsets.UTF_8);
                        ServiceMetaInfo serviceMetaInfo = JSONUtil.toBean(value, ServiceMetaInfo.class);
                        register(serviceMetaInfo);
                    } catch (Exception e) {
                        throw new RuntimeException(key + "续签失败", e);
                    }
                }
            }
        });

        CronUtil.setMatchSecond(true);
        CronUtil.start();
    }

    @Override
    public void watch(String serviceKey) {
        watchers.computeIfAbsent(serviceKey, key -> {
            String prefix = ETCD_ROOT_PATH + key + "/";
            WatchOption option = WatchOption.builder().isPrefix(true).build();
            return client.getWatchClient().watch(
                    ByteSequence.from(prefix, StandardCharsets.UTF_8),
                    option,
                    response -> invalidateOnChange(key, response));
        });
    }

    private void invalidateOnChange(String serviceKey, WatchResponse response) {
        for (WatchEvent event : response.getEvents()) {
            if (event.getEventType() == WatchEvent.EventType.PUT
                    || event.getEventType() == WatchEvent.EventType.DELETE) {
                registryServiceMultiCache.clearCache(serviceKey);
                return;
            }
        }
    }

    int watcherCount() {
        return watchers.size();
    }

    @Override
    public void destroy() {
        log.info("当前 RPC 节点下线");
        for (String key : localRegisterNodeKeySet) {
            try {
                kvClient.delete(ByteSequence.from(key, StandardCharsets.UTF_8)).get();
            } catch (Exception e) {
                log.warn("RPC 节点下线失败, nodeKey={}", key, e);
            }
        }

        for (Watch.Watcher watcher : watchers.values()) {
            watcher.close();
        }
        watchers.clear();
        registryServiceMultiCache.clearAll();
        if (kvClient != null) {
            kvClient.close();
        }
        if (client != null) {
            client.close();
        }
    }
}
