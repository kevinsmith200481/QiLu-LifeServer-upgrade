package gamer.acceptance;

import gamer.config.RegistryConfig;
import gamer.model.ServiceMetaInfo;
import gamer.registry.Registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class RpcTestSupport {

    private RpcTestSupport() {
    }

    static ServiceMetaInfo node(String serviceName, String host, int port) {
        ServiceMetaInfo node = new ServiceMetaInfo();
        node.setServiceName(serviceName);
        node.setServiceHost(host);
        node.setServicePort(port);
        return node;
    }

    static final class MutableRegistry implements Registry {

        private final Map<String, List<ServiceMetaInfo>> services = new ConcurrentHashMap<>();

        private final AtomicInteger discoveries = new AtomicInteger();

        void set(String serviceKey, List<ServiceMetaInfo> nodes) {
            services.put(serviceKey, new ArrayList<>(nodes));
        }

        int discoveryCount() {
            return discoveries.get();
        }

        @Override
        public void init(RegistryConfig registryConfig) {
        }

        @Override
        public void register(ServiceMetaInfo serviceMetaInfo) {
            services.computeIfAbsent(serviceMetaInfo.getServiceKey(), ignored -> new ArrayList<>())
                    .add(serviceMetaInfo);
        }

        @Override
        public void unRegister(ServiceMetaInfo serviceMetaInfo) {
            List<ServiceMetaInfo> nodes = services.get(serviceMetaInfo.getServiceKey());
            if (nodes != null) {
                nodes.remove(serviceMetaInfo);
            }
        }

        @Override
        public List<ServiceMetaInfo> serviceDiscovery(String serviceKey) {
            discoveries.incrementAndGet();
            List<ServiceMetaInfo> nodes = services.get(serviceKey);
            return nodes == null ? Collections.emptyList() : new ArrayList<>(nodes);
        }

        @Override
        public void heartBeat() {
        }

        @Override
        public void watch(String serviceKey) {
        }

        @Override
        public void destroy() {
        }
    }
}
