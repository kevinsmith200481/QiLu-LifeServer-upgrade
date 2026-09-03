package gamer.registry;

import gamer.config.RegistryConfig;
import gamer.model.ServiceMetaInfo;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

/** 默认单测只使用 target 下的文件注册中心；外部 Etcd/ZK 由阶段 4 验收测试负责。 */
public class RegistryTest {

    private final Registry registry = new FileRegistry();

    @Before
    public void init() {
        String file = Paths.get("target", "registry-test", "registry.properties").toAbsolutePath().toString();
        System.setProperty("qilu.rpc.registry.file", file);
        registry.init(new RegistryConfig());
    }

    @After
    public void cleanup() {
        registry.destroy();
        System.clearProperty("qilu.rpc.registry.file");
    }

    @Test
    public void registerDiscoverAndUnregister() throws Exception {
        ServiceMetaInfo node = new ServiceMetaInfo();
        node.setServiceName("registry.TestService");
        node.setServiceHost("127.0.0.1");
        node.setServicePort(1234);

        registry.register(node);
        List<ServiceMetaInfo> discovered = registry.serviceDiscovery(node.getServiceKey());
        Assert.assertEquals(1, discovered.size());
        Assert.assertEquals(node.getServiceNodeKey(), discovered.get(0).getServiceNodeKey());

        registry.unRegister(node);
        Assert.assertTrue(registry.serviceDiscovery(node.getServiceKey()).isEmpty());
    }
}
