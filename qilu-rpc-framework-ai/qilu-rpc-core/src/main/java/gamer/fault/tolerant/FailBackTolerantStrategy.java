package gamer.fault.tolerant;

import gamer.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class FailBackTolerantStrategy implements TolerantStrategy {

    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        Long requestId = context == null ? null : (Long) context.get("requestId");
        return RpcResponse.failure("RPC_FAIL_BACK_UNAVAILABLE", e.getClass().getSimpleName(),
                "Fail-back persistence is not configured", false, requestId);
    }
}
