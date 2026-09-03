package gamer.proxy;

import gamer.model.RpcRequest;
import gamer.model.RpcResponse;
import gamer.model.ServiceMetaInfo;

/** 传输边界，便于跨节点编排与真实 TCP 生命周期分别测试。 */
@FunctionalInterface
public interface RpcTransport {

    RpcResponse invoke(RpcRequest request, ServiceMetaInfo node);
}
