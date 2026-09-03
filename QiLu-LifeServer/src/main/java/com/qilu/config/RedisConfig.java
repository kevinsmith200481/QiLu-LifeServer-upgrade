package com.qilu.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private Integer redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Value("${spring.redis.database:0}")
    private Integer redisDatabase;

    @Bean
    public RedissonClient redissonClient() {
        return Redisson.create(createRedissonConfig());
    }

    Config createRedissonConfig() {
        Config config = new Config();
        configureSingleServer(config);
        return config;
    }

    SingleServerConfig configureSingleServer(Config config) {
        String address = "redis://" + redisHost + ":" + redisPort;
        SingleServerConfig singleServer = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            singleServer.setPassword(redisPassword);
        }
        return singleServer;
    }
}
