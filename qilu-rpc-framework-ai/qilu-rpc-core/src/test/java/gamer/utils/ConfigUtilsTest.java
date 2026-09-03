package gamer.utils;

import gamer.config.RpcConfig;
import org.junit.Assert;
import org.junit.Test;

public class ConfigUtilsTest {

    @Test
    public void loadsSpringActiveProfileBeforeRpcBootstrap() {
        String previous = System.getProperty("spring.profiles.active");
        try {
            System.setProperty("spring.profiles.active", "acceptance");
            RpcConfig config = ConfigUtils.loadConfig(RpcConfig.class, "rpc");
            Assert.assertEquals("rpc-acceptance-test", config.getName());
            Assert.assertEquals(Integer.valueOf(18091), config.getServerPort());
            Assert.assertEquals(Integer.valueOf(250), config.getConnectTimeoutMs());
            Assert.assertEquals(Integer.valueOf(800), config.getRequestTimeoutMs());
            Assert.assertEquals(Integer.valueOf(2), config.getMaxAttempts());
            Assert.assertEquals(Integer.valueOf(20), config.getRetryIntervalMs());
        } finally {
            if (previous == null) {
                System.clearProperty("spring.profiles.active");
            } else {
                System.setProperty("spring.profiles.active", previous);
            }
        }
    }

    @Test
    public void systemPropertyOverridesProfileValue() {
        String previousProfile = System.getProperty("spring.profiles.active");
        String previousTimeout = System.getProperty("rpc.requestTimeoutMs");
        try {
            System.setProperty("spring.profiles.active", "acceptance");
            System.setProperty("rpc.requestTimeoutMs", "90000");

            RpcConfig config = ConfigUtils.loadConfig(RpcConfig.class, "rpc");

            Assert.assertEquals(Integer.valueOf(90000), config.getRequestTimeoutMs());
            Assert.assertEquals(Integer.valueOf(250), config.getConnectTimeoutMs());
        } finally {
            restoreSystemProperty("spring.profiles.active", previousProfile);
            restoreSystemProperty("rpc.requestTimeoutMs", previousTimeout);
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
