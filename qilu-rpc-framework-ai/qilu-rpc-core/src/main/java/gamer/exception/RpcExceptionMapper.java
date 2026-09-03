package gamer.exception;

import java.lang.reflect.InvocationTargetException;

/** 将 Provider 内部异常压缩为可公开、可序列化的错误契约。 */
public final class RpcExceptionMapper {

    private static final int MAX_MESSAGE_LENGTH = 512;

    private RpcExceptionMapper() {
    }

    public static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException
                && ((InvocationTargetException) throwable).getTargetException() != null) {
            return ((InvocationTargetException) throwable).getTargetException();
        }
        return throwable;
    }

    public static String errorCode(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return "RPC_INVALID_ARGUMENT";
        }
        if (throwable instanceof SecurityException) {
            return "RPC_FORBIDDEN";
        }
        if (throwable instanceof IllegalStateException) {
            return "RPC_BUSINESS_STATE";
        }
        return "RPC_PROVIDER_ERROR";
    }

    public static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "Provider invocation failed";
        }
        // 防止换行伪造日志，也避免把超长业务输入带回 Consumer。
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
