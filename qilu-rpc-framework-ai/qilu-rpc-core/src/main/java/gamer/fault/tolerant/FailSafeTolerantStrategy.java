package gamer.fault.tolerant;

import gamer.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class FailSafeTolerantStrategy implements TolerantStrategy {

    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        log.info("静默处理异常", e);
        Long requestId = context == null ? null : (Long) context.get("requestId");
        return RpcResponse.failure("RPC_FAIL_SAFE", e.getClass().getSimpleName(),
                "RPC invocation failed and was handled by fail-safe strategy", false, requestId);
    }
}
