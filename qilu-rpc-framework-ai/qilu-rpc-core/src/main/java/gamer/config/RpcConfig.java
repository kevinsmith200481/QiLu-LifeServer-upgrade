package gamer.config;

import gamer.fault.retry.RetryStrategyKeys;
import gamer.fault.tolerant.TolerantStrategyKeys;
import gamer.loadbalancer.LoadBalancerKeys;
import gamer.serializer.SerializerKeys;
import lombok.Data;

@Data
public class RpcConfig {

    private String name = "qilu-rpc";

    private String version = "1.0";

    private String serverHost = "localhost";

    private Integer serverPort = 8080;

    private String serializer = SerializerKeys.JDK;

    private String loadBalancer = LoadBalancerKeys.ROUND_ROBIN;

    private String retryStrategy = RetryStrategyKeys.NO;

    private String tolerantStrategy = TolerantStrategyKeys.FAIL_FAST;

    /** TCP 建连最长等待时间。该值同时限制单次调用的建连阶段。 */
    private Integer connectTimeoutMs = 1_000;

    /** 单个 Provider 节点从建连到收到完整响应的总预算。 */
    private Integer requestTimeoutMs = 3_000;

    /** 单次业务调用最多尝试的节点数，实际值不会超过当前可用节点数。 */
    private Integer maxAttempts = 3;

    /** 可重试传输故障发生后，切换到下一节点前的等待时间。 */
    private Integer retryIntervalMs = 100;

    private boolean mock = false;

    private RegistryConfig registryConfig = new RegistryConfig();
}
