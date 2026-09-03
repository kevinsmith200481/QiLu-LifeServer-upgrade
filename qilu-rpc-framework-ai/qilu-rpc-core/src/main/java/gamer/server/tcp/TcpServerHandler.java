package gamer.server.tcp;

import gamer.exception.RpcExceptionMapper;
import gamer.context.RpcInvocationContext;
import gamer.model.RpcRequest;
import gamer.model.RpcResponse;
import gamer.protocol.*;
import gamer.registry.LocalRegistry;
import gamer.telemetry.RpcTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.io.IOException;
import java.lang.reflect.Method;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TcpServerHandler implements Handler<NetSocket> {

    @Override
    public void handle(NetSocket socket) {
        TcpBufferHandlerWrapper bufferHandlerWrapper = new TcpBufferHandlerWrapper(buffer -> {
            ProtocolMessage<RpcRequest> protocolMessage;
            try {
                protocolMessage = (ProtocolMessage<RpcRequest>) ProtocolMessageDecoder.decode(buffer);
            } catch (IOException e) {
                throw new RuntimeException("协议消息解码错误");
            }
            RpcRequest rpcRequest = protocolMessage.getBody();
            ProtocolMessage.Header header = protocolMessage.getHeader();

            RpcResponse rpcResponse;
            byte responseStatus;
            Span serverSpan = RpcTelemetry.startServerSpan(rpcRequest);
            try (Scope ignored = serverSpan.makeCurrent();
                 RpcInvocationContext.Scope invocation = RpcInvocationContext.open(rpcRequest)) {
                serverSpan.setAttribute("rpc.system", "qilu-rpc");
                serverSpan.setAttribute("rpc.service", rpcRequest.getServiceName());
                serverSpan.setAttribute("rpc.method", rpcRequest.getMethodName());
                serverSpan.setAttribute("rpc.request_id", header.getRequestId());
                serverSpan.setAttribute("rpc.attempt", RpcInvocationContext.attempt());
                try {
                    Object service = LocalRegistry.getService(rpcRequest.getServiceName());
                    if (service == null) {
                        throw new IllegalStateException("RPC service not found: " + rpcRequest.getServiceName());
                    }
                    Method method = service.getClass().getMethod(rpcRequest.getMethodName(), rpcRequest.getParameterTypes());
                    Object result = method.invoke(service, rpcRequest.getArgs());
                    rpcResponse = RpcResponse.success(result, method.getReturnType(), header.getRequestId());
                    responseStatus = (byte) ProtocolMessageStatusEnum.OK.getValue();
                } catch (Exception e) {
                    Throwable cause = RpcExceptionMapper.unwrap(e);
                    String errorCode = RpcExceptionMapper.errorCode(cause);
                    String safeMessage = RpcExceptionMapper.safeMessage(cause);
                    RpcTelemetry.markError(serverSpan, cause, errorCode);
                    rpcResponse = RpcResponse.failure(errorCode, cause.getClass().getSimpleName(),
                            safeMessage, false, header.getRequestId());
                    responseStatus = (byte) ProtocolMessageStatusEnum.ERROR.getValue();
                    log.warn("rpc provider failure service={} method={} requestId={} traceId={} errorCode={}",
                            rpcRequest.getServiceName(), rpcRequest.getMethodName(), header.getRequestId(),
                            rpcRequest.getTraceId(), errorCode);
                }
            } finally {
                serverSpan.end();
            }

            header.setType((byte) ProtocolMessageTypeEnum.RESPONSE.getKey());
            header.setStatus(responseStatus);
            ProtocolMessage<RpcResponse> responseProtocolMessage = new ProtocolMessage<>(header, rpcResponse);
            try {
                Buffer encode = ProtocolMessageEncoder.encode(responseProtocolMessage);
                socket.write(encode);
            } catch (IOException e) {
                log.warn("rpc response encode failure requestId={}", header.getRequestId(), e);
                socket.close();
            }
        });
        socket.exceptionHandler(error -> log.warn("rpc provider socket error", error));
        socket.handler(bufferHandlerWrapper);
    }

}
