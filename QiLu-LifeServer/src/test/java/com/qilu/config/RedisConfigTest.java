package com.qilu.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisConfigTest {

    @Test
    void createRedissonConfigUsesConfiguredDatabaseAndPassword() {
        RedisConfig redisConfig = new RedisConfig();
        ReflectionTestUtils.setField(redisConfig, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(redisConfig, "redisPort", 16379);
        ReflectionTestUtils.setField(redisConfig, "redisPassword", "acceptance-secret");
        ReflectionTestUtils.setField(redisConfig, "redisDatabase", 15);

        SingleServerConfig singleServer = redisConfig.configureSingleServer(new Config());

        assertEquals("redis://127.0.0.1:16379", singleServer.getAddress());
        assertEquals("acceptance-secret", singleServer.getPassword());
        assertEquals(15, singleServer.getDatabase());
    }

    @Test
    void acceptanceProfileKeepsSpringRedisOnDatabase15() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("acceptance", new ClassPathResource("application-acceptance.yaml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(propertySources);

        assertEquals("127.0.0.1", resolver.getProperty("spring.redis.host"));
        assertEquals("16379", resolver.getProperty("spring.redis.port"));
        assertEquals("15", resolver.getProperty("spring.redis.database"));
    }
}
