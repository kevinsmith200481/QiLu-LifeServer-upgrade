package gamer.server.tcp;

import cn.hutool.core.util.IdUtil;
import gamer.RpcApplication;
import gamer.config.RpcConfig;
import gamer.exception.RpcException;
import gamer.exception.RpcRemoteException;
import gamer.exception.RpcTransportException;
import gamer.model.RpcRequest;
import gamer.model.RpcResponse;
import gamer.model.ServiceMetaInfo;
import gamer.protocol.ProtocolConstant;
import gamer.protocol.ProtocolMessage;
import gamer.protocol.ProtocolMessageDecoder;
import gamer.protocol.ProtocolMessageEncoder;
import gamer.protocol.ProtocolMessageSerializerEnum;
import gamer.protocol.ProtocolMessageStatusEnum;
import gamer.protocol.ProtocolMessageTypeEnum;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 共享 Vert.x/NetClient 的有界 TCP 客户端。
 * 每个请求只持有自己的 socket，并在成功、异常、超时和关闭路径统一释放。
 */
public final class VertxTcpClient {

    private static final Object CLIENT_LOCK = new Object();

    private static volatile Vertx vertx;

    private static volatile NetClient netClient;

    private static volatile int configuredConnectTimeoutMs = -1;

    private static final AtomicInteger ACTIVE_SOCKETS = new AtomicInteger();

    private static final AtomicInteger CREATED_CLIENTS = new AtomicInteger();

    private VertxTcpClient() {
    }

    public static RpcResponse doRequest(RpcRequest rpcRequest, ServiceMetaInfo serviceMetaInfo) {
        return doRequest(rpcRequest, serviceMetaInfo, RpcApplication.getRpcConfig());
    }

    public static RpcResponse doRequest(RpcRequest rpcRequest, ServiceMetaInfo serviceMetaInfo, RpcConfig config) {
        int requestTimeoutMs = positive(config.getRequestTimeoutMs(), 3_000);
        int connectTimeoutMs = Math.min(positive(config.getConnectTimeoutMs(), 1_000), requestTimeoutMs);
        long requestId = rpcRequest.getRequestId() == null
                ? IdUtil.getSnowflakeNextId()
                : rpcRequest.getRequestId();
        rpcRequest.setRequestId(requestId);

        CompletableFuture<RpcResponse> responseFuture = new CompletableFuture<>();
        AtomicReference<NetSocket> socketReference = new AtomicReference<>();
        AtomicBoolean socketCounted = new AtomicBoolean(false);
        AtomicBoolean callFinished = new AtomicBoolean(false);
        NetClient client = getOrCreateClient(connectTimeoutMs);

        client.connect(serviceMetaInfo.getServicePort(), serviceMetaInfo.getServiceHost(), connectResult -> {
            if (connectResult.failed()) {
                responseFuture.completeExceptionally(new RpcTransportException(
                        "RPC_CONNECT_FAILED", "Failed to connect Provider", true,
                        requestId, connectResult.cause()));
                return;
            }

            NetSocket socket = connectResult.result();
            socketReference.set(socket);
            socketCounted.set(true);
            ACTIVE_SOCKETS.incrementAndGet();
            // requestTimeout 可能与 connect 回调同时发生；迟到的成功连接必须立即释放。
            if (callFinished.get()) {
                socket.close();
                releaseSocket(socketCounted);
                return;
            }

            // 所有异步结束信号都必须完成 Future；重复完成由 CompletableFuture 幂等拒绝。
            socket.exceptionHandler(error -> responseFuture.completeExceptionally(
                    new RpcTransportException("RPC_SOCKET_EXCEPTION", "Provider socket exception",
                            true, requestId, error)));
            socket.closeHandler(ignored -> {
                releaseSocket(socketCounted);
                responseFuture.completeExceptionally(new RpcTransportException(
                        "RPC_SOCKET_CLOSED", "Provider socket closed before response", true, requestId));
            });
            socket.endHandler(ignored -> responseFuture.completeExceptionally(
                    new RpcTransportException("RPC_SOCKET_CLOSED",
                            "Provider ended socket before response", true, requestId)));

            socket.handler(new TcpBufferHandlerWrapper(buffer -> decodeResponse(
                    buffer, requestId, responseFuture)));

            try {
                socket.write(encodeRequest(rpcRequest, requestId, config), writeResult -> {
                    if (writeResult.failed()) {
                        responseFuture.completeExceptionally(new RpcTransportException(
                                "RPC_WRITE_FAILED", "Failed to write RPC request", true,
                                requestId, writeResult.cause()));
                    }
                });
            } catch (Exception e) {
                responseFuture.completeExceptionally(new RpcTransportException(
                        "RPC_ENCODE_ERROR", "Failed to encode RPC request", false, requestId, e));
            }
        });

        try {
            return responseFuture.get(requestTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RpcTransportException("RPC_REQUEST_TIMEOUT",
                    "RPC request exceeded " + requestTimeoutMs + " ms", true, requestId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcTransportException("RPC_INTERRUPTED", "RPC request interrupted",
                    false, requestId, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RpcException) {
                throw (RpcException) cause;
            }
            throw new RpcTransportException("RPC_TRANSPORT_ERROR", "RPC transport failed",
                    true, requestId, cause);
        } finally {
            callFinished.set(true);
            NetSocket socket = socketReference.get();
            if (socket != null) {
                socket.close();
                releaseSocket(socketCounted);
            }
        }
    }

    private static Buffer encodeRequest(RpcRequest rpcRequest, long requestId, RpcConfig config) throws IOException {
        ProtocolMessage<RpcRequest> protocolMessage = new ProtocolMessage<>();
        ProtocolMessage.Header header = new ProtocolMessage.Header();
        header.setMagic(ProtocolConstant.PROTOCOL_MAGIC);
        header.setVersion(ProtocolConstant.PROTOCOL_VERSION);
        ProtocolMessageSerializerEnum serializer = ProtocolMessageSerializerEnum.getEnumByValue(
                config.getSerializer());
        if (serializer == null) {
            throw new IOException("Unknown serializer configuration");
        }
        header.setSerializer((byte) serializer.getKey());
        header.setType((byte) ProtocolMessageTypeEnum.REQUEST.getKey());
        header.setRequestId(requestId);
        protocolMessage.setHeader(header);
        protocolMessage.setBody(rpcRequest);
        return ProtocolMessageEncoder.encode(protocolMessage);
    }

    @SuppressWarnings("unchecked")
    private static void decodeResponse(Buffer buffer, long expectedRequestId,
                                       CompletableFuture<RpcResponse> responseFuture) {
        try {
            ProtocolMessage<RpcResponse> message =
                    (ProtocolMessage<RpcResponse>) ProtocolMessageDecoder.decode(buffer);
            ProtocolMessage.Header header = message.getHeader();
            if (header.getRequestId() != expectedRequestId) {
                throw new RpcTransportException("RPC_UNKNOWN_REQUEST_ID",
                        "Response requestId does not match the active request", false, expectedRequestId);
            }
            RpcResponse response = message.getBody();
            if (response == null) {
                throw new RpcTransportException("RPC_EMPTY_RESPONSE", "Provider returned an empty response",
                        true, expectedRequestId);
            }
            ProtocolMessageStatusEnum status = ProtocolMessageStatusEnum.getEnumByValue(header.getStatus());
            if (status == ProtocolMessageStatusEnum.ERROR || !response.isSuccess()) {
                responseFuture.completeExceptionally(new RpcRemoteException(
                        defaultValue(response.getErrorCode(), "RPC_PROVIDER_ERROR"),
                        defaultValue(response.getErrorType(), "ProviderException"),
                        defaultValue(response.getErrorMessage(), "Provider invocation failed"),
                        response.isRetriable(), expectedRequestId));
                return;
            }
            if (status != ProtocolMessageStatusEnum.OK) {
                throw new RpcTransportException("RPC_BAD_PROTOCOL_STATUS",
                        "Provider returned unsupported protocol status", false, expectedRequestId);
            }
            responseFuture.complete(response);
        } catch (RpcException e) {
            responseFuture.completeExceptionally(e);
        } catch (Exception e) {
            responseFuture.completeExceptionally(new RpcTransportException(
                    "RPC_DECODE_ERROR", "Failed to decode RPC response", false,
                    expectedRequestId, e));
        }
    }

    private static NetClient getOrCreateClient(int connectTimeoutMs) {
        NetClient current = netClient;
        if (current != null && configuredConnectTimeoutMs == connectTimeoutMs) {
            return current;
        }
        synchronized (CLIENT_LOCK) {
            if (netClient == null || configuredConnectTimeoutMs != connectTimeoutMs) {
                closeClientLocked();
                vertx = Vertx.vertx();
                netClient = vertx.createNetClient(new NetClientOptions()
                        .setConnectTimeout(connectTimeoutMs));
                configuredConnectTimeoutMs = connectTimeoutMs;
                CREATED_CLIENTS.incrementAndGet();
            }
            return netClient;
        }
    }

    /** 框架关闭或测试结束时显式释放共享 event loop 与 NetClient。 */
    public static void destroy() {
        synchronized (CLIENT_LOCK) {
            closeClientLocked();
        }
    }

    private static void closeClientLocked() {
        if (netClient != null) {
            netClient.close();
            netClient = null;
        }
        if (vertx != null) {
            vertx.close();
            vertx = null;
        }
        configuredConnectTimeoutMs = -1;
    }

    private static void releaseSocket(AtomicBoolean counted) {
        if (counted.compareAndSet(true, false)) {
            ACTIVE_SOCKETS.decrementAndGet();
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    public static int activeSocketCount() {
        return ACTIVE_SOCKETS.get();
    }

    public static int createdClientCount() {
        return CREATED_CLIENTS.get();
    }
}
