package gamer.registry;

import gamer.config.RegistryConfig;
import gamer.model.ServiceMetaInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ZooKeeperRegistry implements Registry {

    private CuratorFramework client;

    private ServiceDiscovery<ServiceMetaInfo> serviceDiscovery;

    private final Set<ServiceMetaInfo> localRegisterServiceSet = ConcurrentHashMap.newKeySet();

    private final RegistryServiceMultiCache registryServiceMultiCache = new RegistryServiceMultiCache();

    /** serviceKey -> CuratorCache，生命周期由 Registry 统一关闭。 */
    private final Map<String, CuratorCache> watchers = new ConcurrentHashMap<>();

    private static final String ZK_ROOT_PATH = "/rpc/zk";


    @Override
    public void init(RegistryConfig registryConfig) {
        client = CuratorFrameworkFactory
                .builder()
                .connectString(registryConfig.getAddress())
                .retryPolicy(new ExponentialBackoffRetry(Math.toIntExact(registryConfig.getTimeout()), 3))
                .build();

        serviceDiscovery = ServiceDiscoveryBuilder.builder(ServiceMetaInfo.class)
                .client(client)
                .basePath(ZK_ROOT_PATH)
                .serializer(new JsonInstanceSerializer<>(ServiceMetaInfo.class))
                .build();

        try {
            client.start();
            serviceDiscovery.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void register(ServiceMetaInfo serviceMetaInfo) throws Exception {
        serviceDiscovery.registerService(buildServiceInstance(serviceMetaInfo));
        localRegisterServiceSet.add(serviceMetaInfo);
    }

    @Override
    public void unRegister(ServiceMetaInfo serviceMetaInfo) {
        try {
            serviceDiscovery.unregisterService(buildServiceInstance(serviceMetaInfo));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        localRegisterServiceSet.remove(serviceMetaInfo);
    }

    @Override
    public List<ServiceMetaInfo> serviceDiscovery(String serviceKey) {
        List<ServiceMetaInfo> cachedServiceMetaInfoList = registryServiceMultiCache.readCache(serviceKey);
        if (cachedServiceMetaInfoList != null) {
            return cachedServiceMetaInfoList;
        }

        try {
            // CuratorCache 覆盖 serviceKey 路径，CREATE/CHANGE/DELETE 都会清理对应缓存。
            watch(serviceKey);
            Collection<ServiceInstance<ServiceMetaInfo>> serviceInstanceList = serviceDiscovery.queryForInstances(serviceKey);

            List<ServiceMetaInfo> serviceMetaInfoList = serviceInstanceList.stream()
                    .map(ServiceInstance::getPayload)
                    .collect(Collectors.toList());

            registryServiceMultiCache.writeCache(serviceKey, serviceMetaInfoList);
            return serviceMetaInfoList;
        } catch (Exception e) {
            throw new RuntimeException("获取服务列表失败", e);
        }
    }

    @Override
    public void heartBeat() {
    }

    @Override
    public void watch(String serviceKey) {
        watchers.computeIfAbsent(serviceKey, key -> {
            String watchPath = ZK_ROOT_PATH + "/" + key;
            CuratorCache curatorCache = CuratorCache.build(client, watchPath);
            CuratorCacheListener listener = CuratorCacheListener.builder()
                    .forCreates(childData -> registryServiceMultiCache.clearCache(key))
                    .forChanges((oldNode, node) -> registryServiceMultiCache.clearCache(key))
                    .forDeletes(childData -> registryServiceMultiCache.clearCache(key))
                    .build();
            curatorCache.listenable().addListener(listener);
            curatorCache.start();
            return curatorCache;
        });
    }

    int watcherCount() {
        return watchers.size();
    }

    @Override
    public void destroy() {
        log.info("当前节点下线");
        for (ServiceMetaInfo serviceMetaInfo : localRegisterServiceSet) {
            try {
                serviceDiscovery.unregisterService(buildServiceInstance(serviceMetaInfo));
            } catch (Exception e) {
                log.warn("RPC 节点下线失败, serviceKey={}", serviceMetaInfo.getServiceKey(), e);
            }
        }

        for (CuratorCache curatorCache : watchers.values()) {
            curatorCache.close();
        }
        watchers.clear();
        registryServiceMultiCache.clearAll();
        try {
            if (serviceDiscovery != null) {
                serviceDiscovery.close();
            }
        } catch (Exception e) {
            log.warn("关闭 Zookeeper service discovery 失败", e);
        }
        if (client != null) {
            client.close();
        }
    }

    private ServiceInstance<ServiceMetaInfo> buildServiceInstance(ServiceMetaInfo serviceMetaInfo) {
        String serviceAddress = serviceMetaInfo.getServiceHost() + ":" + serviceMetaInfo.getServicePort();
        try {
            return ServiceInstance
                    .<ServiceMetaInfo>builder()
                    .id(serviceAddress)
                    .name(serviceMetaInfo.getServiceKey())
                    .address(serviceAddress)
                    .payload(serviceMetaInfo)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
