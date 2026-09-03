package gamer.acceptance;

import gamer.config.RpcConfig;
import gamer.exception.RpcTransportException;
import gamer.model.RpcRequest;
import gamer.model.RpcResponse;
import gamer.model.ServiceMetaInfo;
import gamer.protocol.ProtocolConstant;
import gamer.protocol.ProtocolMessage;
import gamer.protocol.ProtocolMessageEncoder;
import gamer.protocol.ProtocolMessageSerializerEnum;
import gamer.protocol.ProtocolMessageStatusEnum;
import gamer.protocol.ProtocolMessageTypeEnum;
import gamer.server.tcp.VertxTcpClient;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RpcTransportTimeoutTest {

    private final Vertx serverVertx = Vertx.vertx();

    private final List<NetServer> servers = new ArrayList<>();

    @After
    public void tearDown() throws Exception {
        for (NetServer server : servers) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            server.close(result -> closed.complete(null));
            closed.get(2, TimeUnit.SECONDS);
        }
        CompletableFuture<Void> vertxClosed = new CompletableFuture<>();
        serverVertx.close(result -> vertxClosed.complete(null));
        vertxClosed.get(2, TimeUnit.SECONDS);
        VertxTcpClient.destroy();
    }

    @Test
    public void noResponseFinishesAtRequestTimeoutAndDoesNotLeakClientOrSockets() throws Exception {
        int port = startServer(socket -> socket.handler(ignored -> {
            // 故障注入：接受请求但永不返回，验证 requestTimeoutMs 硬边界。
        }));
        RpcConfig config = config(80);
        ServiceMetaInfo node = RpcTestSupport.node("timeout.Service", "127.0.0.1", port);
        int clientsBefore = VertxTcpClient.createdClientCount();
        int clientThreadsAfterFirstRequest = -1;

        for (int i = 0; i < 10; i++) {
            long started = System.nanoTime();
            try {
                VertxTcpClient.doRequest(request("timeout.Service", i + 1L), node, config);
                Assert.fail("timeout expected");
            } catch (RpcTransportException expected) {
                Assert.assertEquals("RPC_REQUEST_TIMEOUT", expected.getErrorCode());
                Assert.assertTrue(expected.isRetriable());
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            Assert.assertTrue("timeout must remain bounded: " + elapsedMs, elapsedMs < 500);
            if (i == 0) {
                clientThreadsAfterFirstRequest = vertxEventLoopThreadCount();
            }
        }

        awaitNoActiveSockets();
        Assert.assertEquals(0, VertxTcpClient.activeSocketCount());
        Assert.assertEquals("same config must reuse one NetClient",
                clientsBefore + 1, VertxTcpClient.createdClientCount());
        Assert.assertTrue("Vert.x event-loop threads must not grow per request",
                vertxEventLoopThreadCount() <= clientThreadsAfterFirstRequest);
    }

    @Test
    public void malformedProtocolCompletesFutureWithStructuredNonRetriableError() throws Exception {
        int port = startServer(socket -> socket.handler(ignored -> {
            Buffer malformed = Buffer.buffer()
                    .appendByte((byte) 0x7f)
                    .appendByte((byte) 1)
                    .appendByte((byte) ProtocolMessageSerializerEnum.JDK.getKey())
                    .appendByte((byte) ProtocolMessageTypeEnum.RESPONSE.getKey())
                    .appendByte((byte) ProtocolMessageStatusEnum.OK.getValue())
                    .appendLong(99L)
                    .appendInt(1)
                    .appendByte((byte) 0);
            socket.write(malformed);
        }));
        RpcConfig config = config(500);
        ServiceMetaInfo node = RpcTestSupport.node("bad.Protocol", "127.0.0.1", port);

        try {
            VertxTcpClient.doRequest(request("bad.Protocol", 7L), node, config);
            Assert.fail("decode error expected");
        } catch (RpcTransportException expected) {
            Assert.assertEquals("RPC_DECODE_ERROR", expected.getErrorCode());
            Assert.assertFalse("bad protocol must not switch nodes", expected.isRetriable());
        }
        awaitNoActiveSockets();
    }

    @Test
    public void connectionFailureCompletesWithoutHanging() {
        RpcConfig config = config(300);
        config.setConnectTimeoutMs(100);
        ServiceMetaInfo node = RpcTestSupport.node("missing.Service", "127.0.0.1", 1);
        long started = System.nanoTime();
        try {
            VertxTcpClient.doRequest(request("missing.Service", 11L), node, config);
            Assert.fail("connect failure expected");
        } catch (RpcTransportException expected) {
            Assert.assertEquals("RPC_CONNECT_FAILED", expected.getErrorCode());
            Assert.assertTrue(expected.isRetriable());
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Assert.assertTrue("connect failure must be bounded: " + elapsedMs, elapsedMs < 1_000);
    }

    @Test
    public void unknownRequestIdReturnsStructuredProtocolError() throws Exception {
        int port = startServer(socket -> socket.handler(ignored -> {
            ProtocolMessage.Header header = new ProtocolMessage.Header();
            header.setMagic(ProtocolConstant.PROTOCOL_MAGIC);
            header.setVersion(ProtocolConstant.PROTOCOL_VERSION);
            header.setSerializer((byte) ProtocolMessageSerializerEnum.JDK.getKey());
            header.setType((byte) ProtocolMessageTypeEnum.RESPONSE.getKey());
            header.setStatus((byte) ProtocolMessageStatusEnum.OK.getValue());
            header.setRequestId(999L);
            ProtocolMessage<RpcResponse> response = new ProtocolMessage<>(header,
                    RpcResponse.success("wrong", String.class, 999L));
            try {
                socket.write(ProtocolMessageEncoder.encode(response));
            } catch (Exception e) {
                socket.close();
            }
        }));
        RpcConfig config = config(500);
        ServiceMetaInfo node = RpcTestSupport.node("unknown.Request", "127.0.0.1", port);

        try {
            VertxTcpClient.doRequest(request("unknown.Request", 21L), node, config);
            Assert.fail("requestId mismatch expected");
        } catch (RpcTransportException expected) {
            Assert.assertEquals("RPC_UNKNOWN_REQUEST_ID", expected.getErrorCode());
            Assert.assertFalse(expected.isRetriable());
        }
    }

    private int startServer(io.vertx.core.Handler<io.vertx.core.net.NetSocket> handler) throws Exception {
        NetServer server = serverVertx.createNetServer().connectHandler(handler);
        CompletableFuture<Integer> port = new CompletableFuture<>();
        server.listen(0, "127.0.0.1", result -> {
            if (result.succeeded()) {
                port.complete(result.result().actualPort());
            } else {
                port.completeExceptionally(result.cause());
            }
        });
        int actualPort = port.get(2, TimeUnit.SECONDS);
        servers.add(server);
        return actualPort;
    }

    private static RpcConfig config(int requestTimeoutMs) {
        RpcConfig config = new RpcConfig();
        config.setConnectTimeoutMs(Math.min(200, requestTimeoutMs));
        config.setRequestTimeoutMs(requestTimeoutMs);
        return config;
    }

    private static RpcRequest request(String serviceName, long requestId) {
        return RpcRequest.builder()
                .serviceName(serviceName)
                .methodName("invoke")
                .parameterTypes(new Class<?>[0])
                .args(new Object[0])
                .requestId(requestId)
                .build();
    }

    private static void awaitNoActiveSockets() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (VertxTcpClient.activeSocketCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static int vertxEventLoopThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("vert.x-eventloop-thread")) {
                count++;
            }
        }
        return count;
    }
}
