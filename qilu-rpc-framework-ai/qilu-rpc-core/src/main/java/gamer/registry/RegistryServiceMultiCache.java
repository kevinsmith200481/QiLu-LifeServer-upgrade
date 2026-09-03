package gamer.registry;

import gamer.model.ServiceMetaInfo;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

public class RegistryServiceMultiCache {

    private final Map<String, List<ServiceMetaInfo>> serviceCache = new ConcurrentHashMap<>();

    void writeCache(String serviceKey, List<ServiceMetaInfo> newServiceCache) {
        // 缓存不可变快照，避免注册中心回调与负载均衡器并发修改同一 List。
        List<ServiceMetaInfo> snapshot = Collections.unmodifiableList(new ArrayList<>(newServiceCache));
        this.serviceCache.put(serviceKey, snapshot);
    }

    List<ServiceMetaInfo> readCache(String serviceKey) {
        return this.serviceCache.get(serviceKey);
    }

    void clearCache(String serviceKey) {
        this.serviceCache.remove(serviceKey);
    }

    void clearAll() {
        this.serviceCache.clear();
    }
}
