package gamer.proxy;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import gamer.RpcApplication;
import gamer.config.RpcConfig;
import gamer.constant.RpcConstant;
import gamer.exception.RpcException;
import gamer.exception.RpcRemoteException;
import gamer.exception.RpcTransportException;
import gamer.fault.tolerant.TolerantStrategy;
import gamer.fault.tolerant.TolerantStrategyFactory;
import gamer.loadbalancer.LoadBalancer;
import gamer.loadbalancer.LoadBalancerFactory;
import gamer.model.RpcRequest;
import gamer.model.RpcResponse;
import gamer.model.ServiceMetaInfo;
import gamer.registry.Registry;
import gamer.registry.RegistryFactory;
import gamer.server.tcp.VertxTcpClient;
import gamer.telemetry.RpcTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consumer 调用编排器：每次 attempt 重新发现服务，并通过 excludedNodes 保证失败节点不被复选。
 * 业务远端异常直接向调用方传播，只有显式可重试的传输异常才会进入下一节点。
 */
@Slf4j
public class ServiceProxy implements InvocationHandler {

    private final Registry registryOverride;

    private final LoadBalancer loadBalancerOverride;

    private final RpcTransport transport;

    private final RpcConfig configOverride;

    public ServiceProxy() {
        this(null, null, VertxTcpClient::doRequest, null);
    }

    /** 测试和嵌入式场景可注入边界实现，不需要修改全局 SPI。 */
    public ServiceProxy(Registry registry, LoadBalancer loadBalancer, RpcTransport transport) {
        this(registry, loadBalancer, transport, null);
    }

    public ServiceProxy(Registry registry, LoadBalancer loadBalancer, RpcTransport transport, RpcConfig config) {
        this.registryOverride = registry;
        this.loadBalancerOverride = loadBalancer;
        this.transport = transport;
        this.configOverride = config;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        RpcConfig config = configOverride == null ? RpcApplication.getRpcConfig() : configOverride;
        String serviceName = method.getDeclaringClass().getName();
        long requestId = IdUtil.getSnowflakeNextId();
        RpcRequest rpcRequest = RpcRequest.builder()
                .serviceName(serviceName)
                .methodName(method.getName())
                .serviceVersion(RpcConstant.DEFAULT_SERVICE_VERSION)
                .parameterTypes(method.getParameterTypes())
                .args(args)
                .requestId(requestId)
                .traceId(MDC.get("traceId"))
                .build();

        Span clientSpan = RpcTelemetry.startClientSpan(serviceName, method.getName(), null);
        try (Scope ignored = clientSpan.makeCurrent()) {
            clientSpan.setAttribute("rpc.system", "qilu-rpc");
            clientSpan.setAttribute("rpc.service", serviceName);
            clientSpan.setAttribute("rpc.method", method.getName());
            clientSpan.setAttribute("rpc.request_id", requestId);
            rpcRequest.setTraceParent(RpcTelemetry.currentTraceParent());
            if (rpcRequest.getTraceId() == null) {
                rpcRequest.setTraceId(RpcTelemetry.currentTraceId());
            }

            Registry registry = registryOverride == null
                ? RegistryFactory.getInstance(config.getRegistryConfig().getRegistry())
                : registryOverride;
        LoadBalancer loadBalancer = loadBalancerOverride == null
                ? LoadBalancerFactory.getInstance(config.getLoadBalancer())
                : loadBalancerOverride;

        String serviceKey = serviceName + ":" + RpcConstant.DEFAULT_SERVICE_VERSION;
        List<ServiceMetaInfo> initialNodes = registry.serviceDiscovery(serviceKey);
            if (CollUtil.isEmpty(initialNodes)) {
                throw new RpcTransportException("RPC_DISCOVERY_EMPTY",
                        "No Provider available for service " + serviceKey, true, requestId);
            }

        int configuredAttempts = positive(config.getMaxAttempts(), 3);
        int attemptLimit = Math.min(configuredAttempts, initialNodes.size());
        // retryIntervalMs 是整次调用的总等待预算，均摊后满足严格总时限公式。
        int retryWaitPerGap = attemptLimit <= 1
                ? 0
                : Math.max(config.getRetryIntervalMs() == null ? 0 : config.getRetryIntervalMs(), 0)
                / (attemptLimit - 1);
        Set<String> excludedNodes = new HashSet<>();
        List<String> nodeSequence = new ArrayList<>();
        RpcTransportException lastTransportFailure = null;

        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            // 第一次复用已读取快照；后续必须重新发现，才能观察注册中心缓存失效结果。
            List<ServiceMetaInfo> discovered = attempt == 1
                    ? initialNodes
                    : registry.serviceDiscovery(serviceKey);
            List<ServiceMetaInfo> eligible = filterEligible(discovered, excludedNodes);
            if (eligible.isEmpty()) {
                break;
            }

            Map<String, Object> requestParams = new HashMap<>();
            requestParams.put("methodName", rpcRequest.getMethodName());
            requestParams.put("requestId", requestId);
            requestParams.put("attempt", attempt);
            ServiceMetaInfo selectedNode = loadBalancer.select(requestParams, eligible);
            if (selectedNode == null) {
                break;
            }
            String nodeKey = selectedNode.getServiceNodeKey();
            nodeSequence.add(nodeKey);
            // attachments 由框架白名单生成，禁止透传任意业务参数或凭据。
            rpcRequest.setAttachments(Collections.singletonMap("rpc.attempt", String.valueOf(attempt)));
            clientSpan.setAttribute("rpc.attempt", attempt);
            clientSpan.setAttribute("rpc.node", nodeKey);

            try {
                RpcResponse response = transport.invoke(rpcRequest, selectedNode);
                if (response == null) {
                    throw new RpcTransportException("RPC_EMPTY_RESPONSE", "Transport returned null",
                            true, requestId);
                }
                if (!response.isSuccess()) {
                    // 注入传输在测试中也必须遵守与真实 TCP 相同的远端错误契约。
                    throw new RpcRemoteException(defaultValue(response.getErrorCode(), "RPC_PROVIDER_ERROR"),
                            defaultValue(response.getErrorType(), "ProviderException"),
                            defaultValue(response.getErrorMessage(), "Provider invocation failed"),
                            response.isRetriable(), requestId);
                }
                log.info("rpc call success service={} method={} node={} attempt={} requestId={} traceId={}",
                        serviceName, method.getName(), nodeKey, attempt, requestId, rpcRequest.getTraceId());
                return response.getData();
            } catch (RpcRemoteException e) {
                // Provider 业务/参数/权限错误禁止访问第二节点。
                log.warn("rpc remote failure service={} method={} node={} attempt={} requestId={} traceId={} errorCode={}",
                        serviceName, method.getName(), nodeKey, attempt, requestId,
                        rpcRequest.getTraceId(), e.getErrorCode());
                throw e;
            } catch (RpcTransportException e) {
                log.warn("rpc transport failure service={} method={} node={} attempt={} requestId={} traceId={} errorCode={}",
                        serviceName, method.getName(), nodeKey, attempt, requestId,
                        rpcRequest.getTraceId(), e.getErrorCode());
                if (!e.isRetriable()) {
                    throw e;
                }
                lastTransportFailure = e;
                excludedNodes.add(nodeKey);
                if (attempt < attemptLimit) {
                    sleepBeforeRetry(retryWaitPerGap, requestId);
                }
            }
        }

        if (lastTransportFailure == null) {
            lastTransportFailure = new RpcTransportException("RPC_NO_ELIGIBLE_NODE",
                    "No untried Provider node remains", false, requestId);
        }
        Map<String, Object> tolerantContext = new LinkedHashMap<>();
        tolerantContext.put("requestId", requestId);
        tolerantContext.put("service", serviceName);
        tolerantContext.put("method", method.getName());
        tolerantContext.put("nodeSequence", nodeSequence);
        TolerantStrategy tolerantStrategy = TolerantStrategyFactory.getInstance(config.getTolerantStrategy());
        RpcResponse tolerantResponse = tolerantStrategy.doTolerant(tolerantContext, lastTransportFailure);
        if (tolerantResponse == null) {
            throw new RpcException("Tolerant strategy returned null", lastTransportFailure);
        }
        if (!tolerantResponse.isSuccess()) {
            // 保留阶段 4 的 tolerant 结果文本，同时让类型化 errorCode 继续指向根传输故障。
            throw new RpcTransportException(lastTransportFailure.getErrorCode(),
                    defaultValue(tolerantResponse.getErrorCode(), "RPC_CALL_FAILED") + ": "
                            + defaultValue(tolerantResponse.getErrorMessage(), "RPC call failed"),
                    lastTransportFailure.isRetriable(), requestId, lastTransportFailure);
        }
            return tolerantResponse.getData();
        } catch (RuntimeException error) {
            String errorCode = error instanceof RpcTransportException
                    ? ((RpcTransportException) error).getErrorCode()
                    : error instanceof RpcRemoteException
                    ? ((RpcRemoteException) error).getErrorCode()
                    : "RPC_CALL_FAILED";
            RpcTelemetry.markError(clientSpan, error, errorCode);
            throw error;
        } finally {
            clientSpan.end();
        }
    }

    private static List<ServiceMetaInfo> filterEligible(List<ServiceMetaInfo> nodes, Set<String> excludedNodes) {
        List<ServiceMetaInfo> eligible = new ArrayList<>();
        if (nodes == null) {
            return eligible;
        }
        for (ServiceMetaInfo node : nodes) {
            if (node != null && !excludedNodes.contains(node.getServiceNodeKey())) {
                eligible.add(node);
            }
        }
        return eligible;
    }

    private static void sleepBeforeRetry(int interval, long requestId) {
        if (interval == 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcTransportException("RPC_RETRY_INTERRUPTED", "Retry wait interrupted",
                    false, requestId, e);
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
