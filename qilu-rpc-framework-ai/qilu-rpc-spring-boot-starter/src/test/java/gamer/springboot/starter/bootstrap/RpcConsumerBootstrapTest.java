package gamer.springboot.starter.bootstrap;

import gamer.RpcApplication;
import gamer.springboot.starter.annotation.RpcReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.aop.framework.ProxyFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RpcConsumerBootstrapTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void tearDown() {
        RpcApplication.destroy();
        System.clearProperty("qilu.rpc.registry.file");
    }

    @Test
    void injectsReferenceIntoTargetBeforeAopProxyCreation() {
        System.setProperty("qilu.rpc.registry.file", tempDirectory.resolve("registry.properties").toString());
        RpcBackedService target = new RpcBackedService();
        RpcConsumerBootstrap bootstrap = new RpcConsumerBootstrap();

        bootstrap.postProcessProperties(new MutablePropertyValues(), target, "rpcBackedService");

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        RpcBackedService proxy = (RpcBackedService) proxyFactory.getProxy();
        assertThat(proxy.hasRpcReference()).isTrue();
    }

    interface RemoteService {
        String ping();
    }

    static class RpcBackedService {
        @RpcReference(interfaceClass = RemoteService.class)
        private RemoteService remoteService;

        boolean hasRpcReference() {
            return remoteService != null;
        }
    }
}
