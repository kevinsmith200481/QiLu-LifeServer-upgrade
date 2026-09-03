package gamer.fault.tolerant;

import gamer.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class FailOverTolerantStrategy implements TolerantStrategy {

    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        Long requestId = context == null ? null : (Long) context.get("requestId");
        // 节点切换已由 ServiceProxy 完成；这里给“节点耗尽”一个非 null 的明确结果。
        return RpcResponse.failure("RPC_FAILOVER_EXHAUSTED", "RpcTransportException",
                "All eligible Provider nodes have been exhausted", false, requestId);
    }
}
