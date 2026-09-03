package gamer.loadbalancer;

import gamer.spi.SpiLoader;

public class LoadBalancerFactory {

    static {
        SpiLoader.load(LoadBalancer.class);
    }

    private static final LoadBalancer DEFAULT_LOAD_BALANCER = new RoundRobinLoadBalancer();

    public static LoadBalancer getInstance(String key) {
        return SpiLoader.getInstance(LoadBalancer.class, key);
    }

}
