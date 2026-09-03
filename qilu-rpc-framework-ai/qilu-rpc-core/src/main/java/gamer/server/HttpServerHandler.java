package gamer.server;

import gamer.RpcApplication;
import gamer.exception.RpcExceptionMapper;
import gamer.model.RpcRequest;
import gamer.model.RpcResponse;
import gamer.registry.LocalRegistry;
import gamer.serializer.Serializer;
import gamer.serializer.SerializerFactory;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.io.IOException;
import java.lang.reflect.Method;

public class HttpServerHandler implements Handler<HttpServerRequest> {

    @Override
    public void handle(HttpServerRequest request) {
        final Serializer serializer = SerializerFactory.getInstance(RpcApplication.getRpcConfig().getSerializer());

        System.out.println("Received request: " + request.method() + " " + request.uri());

        Serializer finalSerializer = serializer;
        request.bodyHandler(body -> {
            byte[] bytes = body.getBytes();
            RpcRequest rpcRequest = null;
            try {
                rpcRequest = finalSerializer.deserialize(bytes, RpcRequest.class);
            } catch (Exception e) {
                e.printStackTrace();
            }

            RpcResponse rpcResponse;
            if (rpcRequest == null) {
                rpcResponse = RpcResponse.failure("RPC_BAD_REQUEST", "BadRequest",
                        "rpcRequest is null", false, null);
                doResponse(request, rpcResponse, finalSerializer);
                return;
            }

            try {
                Object service = LocalRegistry.getService(rpcRequest.getServiceName());
                if (service == null) {
                    throw new IllegalStateException("RPC service not found: " + rpcRequest.getServiceName());
                }
                Method method = service.getClass().getMethod(rpcRequest.getMethodName(), rpcRequest.getParameterTypes());
                Object result = method.invoke(service, rpcRequest.getArgs());
                long requestId = rpcRequest.getRequestId() == null ? 0L : rpcRequest.getRequestId();
                rpcResponse = RpcResponse.success(result, method.getReturnType(), requestId);
            } catch (Exception e) {
                Throwable cause = RpcExceptionMapper.unwrap(e);
                rpcResponse = RpcResponse.failure(RpcExceptionMapper.errorCode(cause),
                        cause.getClass().getSimpleName(), RpcExceptionMapper.safeMessage(cause),
                        false, rpcRequest.getRequestId());
            }
            doResponse(request, rpcResponse, finalSerializer);
        });
    }

    void doResponse(HttpServerRequest request, RpcResponse rpcResponse, Serializer serializer) {
        HttpServerResponse httpServerResponse = request.response()
                .putHeader("content-type", "application/json");
        try {
            byte[] serialized = serializer.serialize(rpcResponse);
            httpServerResponse.end(Buffer.buffer(serialized));
        } catch (IOException e) {
            e.printStackTrace();
            httpServerResponse.end(Buffer.buffer());
        }
    }
}
