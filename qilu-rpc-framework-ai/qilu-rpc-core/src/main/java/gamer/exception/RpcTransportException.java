package gamer.exception;

import lombok.Getter;

/**
 * Consumer 本地传输异常。只有 retriable=true 的异常才允许 ServiceProxy 换节点，
 * 从类型层面阻止业务异常被误当作网络抖动重试。
 */
@Getter
public class RpcTransportException extends RpcException {

    private final String errorCode;

    private final boolean retriable;

    private final long requestId;

    public RpcTransportException(String errorCode, String message, boolean retriable,
                                 long requestId, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retriable = retriable;
        this.requestId = requestId;
    }

    public RpcTransportException(String errorCode, String message, boolean retriable, long requestId) {
        this(errorCode, message, retriable, requestId, null);
    }
}
