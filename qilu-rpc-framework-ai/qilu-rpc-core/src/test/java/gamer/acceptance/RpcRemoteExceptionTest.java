package gamer.acceptance;

import gamer.config.RpcConfig;
import gamer.exception.RpcRemoteException;
import gamer.model.RpcResponse;
import gamer.model.ServiceMetaInfo;
import gamer.proxy.ServiceProxy;
import gamer.model.RpcRequest;
import gamer.registry.LocalRegistry;
import gamer.server.tcp.TcpServerHandler;
import gamer.server.tcp.VertxTcpClient;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RpcRemoteExceptionTest {

    private Vertx vertx;

    private NetServer server;

    interface BusinessService {
        String execute(String value);
    }

    public static class FailingBusinessService implements BusinessService {
        @Override
        public String execute(String value) {
            throw new IllegalStateException("unsafe\r\nstate");
        }
    }

    @After
    public void tearDown() throws Exception {
        LocalRegistry.remove(BusinessService.class.getName());
        if (server != null) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            server.close(result -> closed.complete(null));
            closed.get(2, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            vertx.close(result -> closed.complete(null));
            closed.get(2, TimeUnit.SECONDS);
        }
        VertxTcpClient.destroy();
    }

    @Test
    public void providerBusinessExceptionIsNotRetriedOnNodeB() throws Exception {
        Method method = BusinessService.class.getMethod("execute", String.class);
        String serviceKey = BusinessService.class.getName() + ":1.0";
        ServiceMetaInfo nodeA = RpcTestSupport.node(BusinessService.class.getName(), "127.0.0.1", 18201);
        ServiceMetaInfo nodeB = RpcTestSupport.node(BusinessService.class.getName(), "127.0.0.1", 18202);
        RpcTestSupport.MutableRegistry registry = new RpcTestSupport.MutableRegistry();
        registry.set(serviceKey, Arrays.asList(nodeA, nodeB));
        AtomicInteger calls = new AtomicInteger();
        RpcConfig config = new RpcConfig();
        config.setMaxAttempts(2);
        config.setRetryIntervalMs(0);

        ServiceProxy proxy = new ServiceProxy(registry, (params, nodes) -> nodes.get(0), (request, node) -> {
            calls.incrementAndGet();
            return RpcResponse.failure("RPC_BUSINESS_STATE", "IllegalStateException",
                    "order state does not allow this operation", false, request.getRequestId());
        }, config);

        try {
            proxy.invoke(null, method, new Object[]{"x"});
            Assert.fail("remote exception expected");
        } catch (RpcRemoteException expected) {
            Assert.assertEquals("RPC_BUSINESS_STATE", expected.getErrorCode());
            Assert.assertEquals("IllegalStateException", expected.getErrorType());
            Assert.assertFalse(expected.isRetriable());
            Assert.assertTrue(expected.getRequestId() > 0);
        }
        Assert.assertEquals("business failure must be attempt=1", 1, calls.get());
    }

    @Test
    public void tcpProviderUnwrapsInvocationExceptionAndReturnsSafeContract() throws Exception {
        LocalRegistry.registerInstance(BusinessService.class.getName(), new FailingBusinessService());
        vertx = Vertx.vertx();
        server = vertx.createNetServer().connectHandler(new TcpServerHandler());
        CompletableFuture<Integer> listening = new CompletableFuture<>();
        server.listen(0, "127.0.0.1", result -> {
            if (result.succeeded()) {
                listening.complete(result.result().actualPort());
            } else {
                listening.completeExceptionally(result.cause());
            }
        });
        int port = listening.get(2, TimeUnit.SECONDS);
        RpcConfig config = new RpcConfig();
        config.setConnectTimeoutMs(200);
        config.setRequestTimeoutMs(1_000);
        ServiceMetaInfo node = RpcTestSupport.node(BusinessService.class.getName(), "127.0.0.1", port);
        RpcRequest request = RpcRequest.builder()
                .serviceName(BusinessService.class.getName())
                .methodName("execute")
                .parameterTypes(new Class<?>[]{String.class})
                .args(new Object[]{"value"})
                .requestId(991L)
                .build();

        try {
            VertxTcpClient.doRequest(request, node, config);
            Assert.fail("remote exception expected");
        } catch (RpcRemoteException expected) {
            Assert.assertEquals("RPC_BUSINESS_STATE", expected.getErrorCode());
            Assert.assertEquals("IllegalStateException", expected.getErrorType());
            Assert.assertEquals("unsafe  state", expected.getMessage());
            Assert.assertEquals(991L, expected.getRequestId());
            Assert.assertFalse(expected.isRetriable());
        }
    }
}
