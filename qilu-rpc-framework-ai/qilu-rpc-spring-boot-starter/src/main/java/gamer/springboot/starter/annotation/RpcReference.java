package gamer.springboot.starter.annotation;

import gamer.constant.RpcConstant;
import gamer.fault.retry.RetryStrategyKeys;
import gamer.fault.tolerant.TolerantStrategyKeys;
import gamer.loadbalancer.LoadBalancerKeys;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RpcReference {

    Class<?> interfaceClass() default void.class;

    String serviceVersion() default RpcConstant.DEFAULT_SERVICE_VERSION;

    String loadBalancer() default LoadBalancerKeys.ROUND_ROBIN;

    String retryStrategy() default RetryStrategyKeys.NO;

    String tolerantStrategy() default TolerantStrategyKeys.FAIL_FAST;

    boolean mock() default false;

}
