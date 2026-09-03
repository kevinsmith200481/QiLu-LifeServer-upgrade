package gamer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcResponse implements Serializable {

    /** 明确区分正常返回与远端失败，避免再通过 null/Throwable 猜测。 */
    private boolean success;

    private Object data;

    private Class<?> dataType;

    private String message;

    private String errorCode;

    private String errorType;

    private String errorMessage;

    /** 仅表示传输层是否允许换节点；Provider 业务异常始终为 false。 */
    private boolean retriable;

    private Long requestId;

    public static RpcResponse success(Object data, Class<?> dataType, long requestId) {
        return RpcResponse.builder()
                .success(true)
                .data(data)
                .dataType(dataType)
                .message("ok")
                .requestId(requestId)
                .build();
    }

    public static RpcResponse failure(String errorCode, String errorType, String errorMessage,
                                      boolean retriable, Long requestId) {
        return RpcResponse.builder()
                .success(false)
                .message(errorMessage)
                .errorCode(errorCode)
                .errorType(errorType)
                .errorMessage(errorMessage)
                .retriable(retriable)
                .requestId(requestId)
                .build();
    }

}
