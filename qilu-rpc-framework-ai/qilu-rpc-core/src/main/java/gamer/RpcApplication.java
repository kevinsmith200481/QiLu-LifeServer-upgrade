package gamer;

import gamer.config.RegistryConfig;
import gamer.config.RpcConfig;
import gamer.constant.RpcConstant;
import gamer.registry.Registry;
import gamer.registry.RegistryFactory;
import gamer.utils.ConfigUtils;
import gamer.server.tcp.VertxTcpClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcApplication {

    private static volatile RpcConfig rpcConfig;

    private static volatile Registry activeRegistry;

    private static volatile boolean shutdownHookRegistered;

    public static void init(RpcConfig newRpcConfig) {
        rpcConfig = newRpcConfig;
        log.info("rpc init, config = {}", newRpcConfig.toString());
        RegistryConfig registryConfig = rpcConfig.getRegistryConfig();
        Registry registry = RegistryFactory.getInstance(registryConfig.getRegistry());
        registry.init(registryConfig);
        activeRegistry = registry;
        log.info("registry init, config = {}", registryConfig);
        if (!shutdownHookRegistered) {
            synchronized (RpcApplication.class) {
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(new Thread(RpcApplication::destroy, "qilu-rpc-shutdown"));
                    shutdownHookRegistered = true;
                }
            }
        }
    }

    public static void init() {
        RpcConfig newRpcConfig;
        try {
            newRpcConfig = ConfigUtils.loadConfig(RpcConfig.class, RpcConstant.DEFAULT_CONFIG_PREFIX);
        } catch (Exception e) {
            newRpcConfig = new RpcConfig();
        }
        init(newRpcConfig);
    }


    public static RpcConfig getRpcConfig() {
        if (rpcConfig == null) {
            synchronized (RpcApplication.class) {
                if (rpcConfig == null) {
                    init();
                }
            }
        }
        return rpcConfig;
    }

    /** 统一关闭注册中心和共享 TCP 客户端，供 Spring/JVM 生命周期调用。 */
    public static void destroy() {
        Registry registry = activeRegistry;
        activeRegistry = null;
        if (registry != null) {
            registry.destroy();
        }
        VertxTcpClient.destroy();
    }
}
