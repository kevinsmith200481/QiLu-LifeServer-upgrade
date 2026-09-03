package com.qilu.config;

import com.qilu.ai.api.error.AiFailureCode;
import gamer.exception.RpcRemoteException;
import gamer.exception.RpcTransportException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.lang.reflect.UndeclaredThrowableException;

/** 将线程池、RPC transport 和远端异常收敛为对外稳定错误码。 */
public final class AiFailureMapper {

    private AiFailureMapper() {
    }

    public static AiFailureCode from(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof RejectedExecutionException) {
            return AiFailureCode.RATE_LIMITED;
        }
        if (cause instanceof TimeoutException) {
            return AiFailureCode.RPC_REQUEST_TIMEOUT;
        }
        if (cause instanceof RpcRemoteException) {
            return AiFailureCode.RPC_REMOTE_ERROR;
        }
        if (cause instanceof RpcTransportException) {
            return fromRpcTransport(((RpcTransportException) cause).getErrorCode());
        }
        return AiFailureCode.RPC_REMOTE_ERROR;
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            if (current instanceof ExecutionException
                    || current instanceof java.util.concurrent.CompletionException
                    || current instanceof UndeclaredThrowableException) {
                current = current.getCause();
                continue;
            }
            // Dynamic proxies and tolerant strategies may wrap the typed RPC
            // failure in a generic RpcException; retain the deepest typed cause.
            if (!(current instanceof RpcTransportException)
                    && !(current instanceof RpcRemoteException)
                    && (current.getCause() instanceof RpcTransportException
                    || current.getCause() instanceof RpcRemoteException)) {
                current = current.getCause();
                continue;
            }
            break;
        }
        return current;
    }

    private static AiFailureCode fromRpcTransport(String code) {
        if ("RPC_DISCOVERY_EMPTY".equals(code) || "RPC_NO_ELIGIBLE_NODE".equals(code)) {
            return AiFailureCode.RPC_DISCOVERY_EMPTY;
        }
        if ("RPC_REQUEST_TIMEOUT".equals(code) || "RPC_INTERRUPTED".equals(code)) {
            return AiFailureCode.RPC_REQUEST_TIMEOUT;
        }
        if (code != null && (code.contains("CONNECT") || code.contains("SOCKET"))) {
            return AiFailureCode.RPC_CONNECT_TIMEOUT;
        }
        return AiFailureCode.RPC_REMOTE_ERROR;
    }
}
