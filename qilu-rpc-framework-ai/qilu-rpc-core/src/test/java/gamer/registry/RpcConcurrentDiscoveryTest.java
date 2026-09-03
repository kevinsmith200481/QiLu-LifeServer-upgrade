package gamer.registry;

import gamer.model.ServiceMetaInfo;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class RpcConcurrentDiscoveryTest {

    @Test
    public void twoServiceKeysRemainIsolatedAcrossOneHundredConcurrentReads() throws Exception {
        RegistryServiceMultiCache cache = new RegistryServiceMultiCache();
        ServiceMetaInfo alpha = node("alpha.Service", 19001);
        ServiceMetaInfo beta = node("beta.Service", 19002);
        cache.writeCache(alpha.getServiceKey(), Collections.singletonList(alpha));
        cache.writeCache(beta.getServiceKey(), Collections.singletonList(beta));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        Future<Void>[] futures = new Future[100];
        for (int i = 0; i < futures.length; i++) {
            final boolean readAlpha = i % 2 == 0;
            futures[i] = executor.submit(() -> {
                start.await();
                String key = readAlpha ? alpha.getServiceKey() : beta.getServiceKey();
                List<ServiceMetaInfo> result = cache.readCache(key);
                Assert.assertNotNull(result);
                Assert.assertEquals(1, result.size());
                Assert.assertEquals(key, result.get(0).getServiceKey());
                try {
                    result.clear();
                    Assert.fail("cache snapshot must be immutable");
                } catch (UnsupportedOperationException expected) {
                    // 调用方不能反向污染共享注册中心快照。
                }
                return null;
            });
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(2, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
        Assert.assertEquals(alpha, cache.readCache(alpha.getServiceKey()).get(0));
        Assert.assertEquals(beta, cache.readCache(beta.getServiceKey()).get(0));
    }

    private static ServiceMetaInfo node(String serviceName, int port) {
        ServiceMetaInfo node = new ServiceMetaInfo();
        node.setServiceName(serviceName);
        node.setServiceHost("127.0.0.1");
        node.setServicePort(port);
        return node;
    }
}
