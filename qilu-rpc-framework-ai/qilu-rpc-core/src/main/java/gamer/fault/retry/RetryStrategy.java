package gamer.fault.retry;

import gamer.model.RpcResponse;

import java.util.concurrent.Callable;

public interface RetryStrategy {

    RpcResponse doRetry(Callable<RpcResponse> callable) throws Exception;
}
