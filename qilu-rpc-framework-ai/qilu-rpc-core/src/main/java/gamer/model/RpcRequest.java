package gamer.model;

import gamer.constant.RpcConstant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcRequest implements Serializable {

    private String serviceName;

    private String methodName;

    private String serviceVersion = RpcConstant.DEFAULT_SERVICE_VERSION;

    private Class<?>[] parameterTypes;

    private Object[] args;

    /** Consumer 生成的请求标识，协议头、日志和异常均复用该值。 */
    private Long requestId;

    /** 对外业务 traceId，仅用于日志和响应关联。 */
    private String traceId;

    /** W3C Trace Context；RPC client 注入，RPC server 提取。 */
    private String traceParent;

    /**
     * 只允许框架写入的安全元数据。不得放 token、手机号、完整参数或业务正文。
     */
    private Map<String, String> attachments;

}
