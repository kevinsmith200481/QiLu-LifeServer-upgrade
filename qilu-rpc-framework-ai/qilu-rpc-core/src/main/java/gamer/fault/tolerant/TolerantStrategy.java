package gamer.fault.tolerant;

import gamer.model.RpcResponse;

import java.util.Map;

public interface TolerantStrategy {

    RpcResponse doTolerant(Map<String, Object> context, Exception e);
}
