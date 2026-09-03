package gamer.registry;

import gamer.config.RegistryConfig;
import gamer.model.ServiceMetaInfo;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 使用 acceptance 系统属性时对真实 Etcd/Zookeeper 执行相同缓存失效契约。 */
public class RegistryCacheInvalidationTest {

    @Test
    public void etcdPutDeleteUpdateInvalidateServiceCacheWithinTwoSeconds() throws Exception {
        String endpoint = System.getProperty("rpc.acceptance.etcd");
        Assume.assumeTrue("external Etcd acceptance is opt-in", endpoint != null && !endpoint.trim().isEmpty());
        EtcdRegistry registry = new EtcdRegistry();
        verifyRegistryContract(registry, endpoint, true);
    }

    @Test
    public void zookeeperCreateChangeDeleteInvalidateServiceCacheWithinTwoSeconds() throws Exception {
        String endpoint = System.getProperty("rpc.acceptance.zookeeper");
        Assume.assumeTrue("external Zookeeper acceptance is opt-in", endpoint != null && !endpoint.trim().isEmpty());
        ZooKeeperRegistry registry = new ZooKeeperRegistry();
        verifyRegistryContract(registry, endpoint, false);
    }

    private void verifyRegistryContract(Registry registry, String endpoint, boolean etcd) throws Exception {
        RegistryConfig config = new RegistryConfig();
        config.setAddress(endpoint);
        config.setTimeout(500L);
        registry.init(config);
        String serviceName = "acceptance.CacheService" + System.nanoTime();
        ServiceMetaInfo nodeA = node(serviceName, 19101, "v1");
        ServiceMetaInfo nodeB = node(serviceName, 19102, "v1");
        try {
            registry.register(nodeA);
            await("initial discovery", () -> contains(registry.serviceDiscovery(nodeA.getServiceKey()), 19101, "v1"));

            // 100 个并发首次发现只能为该 serviceKey 建立一个监听器。
            runConcurrentDiscovery(registry, nodeA.getServiceKey());
            Assert.assertEquals(1, etcd
                    ? ((EtcdRegistry) registry).watcherCount()
                    : ((ZooKeeperRegistry) registry).watcherCount());

            registry.register(nodeB);
            await("create/put invalidation", () -> registry.serviceDiscovery(nodeA.getServiceKey()).size() == 2);

            ServiceMetaInfo updatedA = node(serviceName, 19101, "v2");
            registry.register(updatedA);
            await("change/put invalidation",
                    () -> contains(registry.serviceDiscovery(nodeA.getServiceKey()), 19101, "v2"));

            registry.unRegister(nodeB);
            await("delete invalidation",
                    () -> registry.serviceDiscovery(nodeA.getServiceKey()).size() == 1);

            // 连续 10 次变化均必须让下一次 discovery 看到最新列表。
            for (int i = 0; i < 10; i++) {
                ServiceMetaInfo transientNode = node(serviceName, 19200 + i, "round-" + i);
                registry.register(transientNode);
                await("round " + i + " create",
                        () -> contains(registry.serviceDiscovery(nodeA.getServiceKey()),
                                transientNode.getServicePort(), transientNode.getServiceGroup()));
                registry.unRegister(transientNode);
                await("round " + i + " delete",
                        () -> !containsPort(registry.serviceDiscovery(nodeA.getServiceKey()),
                                transientNode.getServicePort()));
            }
            registry.unRegister(updatedA);
        } finally {
            try {
                registry.unRegister(nodeA);
                registry.unRegister(nodeB);
            } catch (Exception ignored) {
                // destroy 仍会关闭客户端；清理异常不能掩盖主断言。
            }
            registry.destroy();
        }
    }

    private static void runConcurrentDiscovery(Registry registry, String serviceKey) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        Future<List<ServiceMetaInfo>>[] futures = new Future[100];
        for (int i = 0; i < futures.length; i++) {
            futures[i] = executor.submit(() -> {
                start.await();
                return registry.serviceDiscovery(serviceKey);
            });
        }
        start.countDown();
        for (Future<List<ServiceMetaInfo>> future : futures) {
            Assert.assertFalse(future.get(2, TimeUnit.SECONDS).isEmpty());
        }
        executor.shutdownNow();
    }

    private static void await(String label, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (Throwable e) {
                lastFailure = e;
            }
            Thread.sleep(20);
        }
        AssertionError error = new AssertionError(label + " was not visible within 2 seconds");
        if (lastFailure != null) {
            error.initCause(lastFailure);
        }
        throw error;
    }

    private static ServiceMetaInfo node(String serviceName, int port, String group) {
        ServiceMetaInfo node = new ServiceMetaInfo();
        node.setServiceName(serviceName);
        node.setServiceHost("127.0.0.1");
        node.setServicePort(port);
        node.setServiceGroup(group);
        return node;
    }

    private static boolean contains(List<ServiceMetaInfo> nodes, int port, String group) {
        for (ServiceMetaInfo node : nodes) {
            if (Integer.valueOf(port).equals(node.getServicePort()) && group.equals(node.getServiceGroup())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPort(List<ServiceMetaInfo> nodes, int port) {
        for (ServiceMetaInfo node : nodes) {
            if (Integer.valueOf(port).equals(node.getServicePort())) {
                return true;
            }
        }
        return false;
    }
}
