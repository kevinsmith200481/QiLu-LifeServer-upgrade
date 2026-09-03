package gamer.acceptance;

import gamer.config.RpcConfig;
import gamer.exception.RpcException;
import gamer.exception.RpcTransportException;
import gamer.fault.tolerant.TolerantStrategyKeys;
import gamer.loadbalancer.LoadBalancer;
import gamer.model.RpcResponse;
import gamer.model.ServiceMetaInfo;
import gamer.proxy.ServiceProxy;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RpcFailoverIntegrationTest {

    interface EchoService {
        String echo(String value);
    }

    @Test
    public void connectionFailureSwitchesFromAtoBWithoutReusingA() throws Exception {
        Method method = EchoService.class.getMethod("echo", String.class);
        String serviceKey = EchoService.class.getName() + ":1.0";
        ServiceMetaInfo nodeA = RpcTestSupport.node(EchoService.class.getName(), "127.0.0.1", 18081);
        ServiceMetaInfo nodeB = RpcTestSupport.node(EchoService.class.getName(), "127.0.0.1", 18082);
        RpcTestSupport.MutableRegistry registry = new RpcTestSupport.MutableRegistry();
        registry.set(serviceKey, Arrays.asList(nodeA, nodeB));
        List<String> selected = new ArrayList<>();

        ServiceProxy proxy = new ServiceProxy(registry, firstNode(), (request, node) -> {
            selected.add(node.getServiceNodeKey());
            if (node.getServicePort() == 18081) {
                throw new RpcTransportException("RPC_CONNECT_FAILED", "A is down",
                        true, request.getRequestId());
            }
            return RpcResponse.success("from-b", String.class, request.getRequestId());
        }, config(2, 0));

        Object result = proxy.invoke(null, method, new Object[]{"hello"});
        Assert.assertEquals("from-b", result);
        Assert.assertEquals(Arrays.asList(nodeA.getServiceNodeKey(), nodeB.getServiceNodeKey()), selected);
        Assert.assertEquals(2, selected.stream().distinct().count());
        Assert.assertTrue("second attempt must rediscover", registry.discoveryCount() >= 2);
    }

    @Test
    public void exhaustedNodesFinishInsideConfiguredBudgetAndRemainUnique() throws Exception {
        Method method = EchoService.class.getMethod("echo", String.class);
        String serviceKey = EchoService.class.getName() + ":1.0";
        List<ServiceMetaInfo> nodes = Arrays.asList(
                RpcTestSupport.node(EchoService.class.getName(), "127.0.0.1", 18101),
                RpcTestSupport.node(EchoService.class.getName(), "127.0.0.1", 18102),
                RpcTestSupport.node(EchoService.class.getName(), "127.0.0.1", 18103));
        RpcTestSupport.MutableRegistry registry = new RpcTestSupport.MutableRegistry();
        registry.set(serviceKey, nodes);
        List<String> selected = new ArrayList<>();
        RpcConfig config = config(3, 10);
        config.setRequestTimeoutMs(100);
        config.setTolerantStrategy(TolerantStrategyKeys.FAIL_OVER);
        ServiceProxy proxy = new ServiceProxy(registry, firstNode(), (request, node) -> {
            selected.add(node.getServiceNodeKey());
            throw new RpcTransportException("RPC_CONNECT_FAILED", "down",
                    true, request.getRequestId());
        }, config);

        long started = System.nanoTime();
        try {
            proxy.invoke(null, method, new Object[]{"hello"});
            Assert.fail("all nodes should fail");
        } catch (RpcException expected) {
            Assert.assertTrue(expected.getMessage().contains("RPC_FAILOVER_EXHAUSTED"));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        long budgetMs = config.getRequestTimeoutMs() * config.getMaxAttempts()
                + config.getRetryIntervalMs();
        Assert.assertTrue("elapsed=" + elapsedMs + ", budget=" + budgetMs, elapsedMs < budgetMs);
        Assert.assertEquals(3, selected.size());
        Assert.assertEquals(3, selected.stream().distinct().count());
    }

    private static LoadBalancer firstNode() {
        return (params, nodes) -> nodes.get(0);
    }

    private static RpcConfig config(int maxAttempts, int retryIntervalMs) {
        RpcConfig config = new RpcConfig();
        config.setMaxAttempts(maxAttempts);
        config.setRetryIntervalMs(retryIntervalMs);
        config.setTolerantStrategy(TolerantStrategyKeys.FAIL_FAST);
        return config;
    }
}
