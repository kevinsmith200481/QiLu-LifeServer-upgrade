package gamer.exception;

import lombok.Getter;

/** Provider 返回的安全化远端异常；不携带服务端 Throwable 或堆栈。 */
@Getter
public class RpcRemoteException extends RpcException {

    private final String errorCode;

    private final String errorType;

    private final boolean retriable;

    private final long requestId;

    public RpcRemoteException(String errorCode, String errorType, String message,
                              boolean retriable, long requestId) {
        super(message);
        this.errorCode = errorCode;
        this.errorType = errorType;
        this.retriable = retriable;
        this.requestId = requestId;
    }
}
