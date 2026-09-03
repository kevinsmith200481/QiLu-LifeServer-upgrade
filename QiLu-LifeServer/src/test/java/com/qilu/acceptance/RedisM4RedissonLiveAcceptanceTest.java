package com.qilu.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "acceptance.redis-m4", matches = "true")
class RedisM4RedissonLiveAcceptanceTest {

    @Test
    void watchdogRenewsAndUnlockRemovesTheDb15Lock() throws Exception {
        String host = required("QILU_REDIS_HOST");
        String port = required("QILU_REDIS_PORT");
        String password = required("QILU_REDIS_PASSWORD");
        String runId = System.getProperty("acceptance.redis-m4.run-id", "manual");

        Config config = new Config();
        config.setLockWatchdogTimeout(3_000L);
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password)
                .setDatabase(15);

        RedissonClient client = Redisson.create(config);
        RLock lock = client.getLock("migration:test:" + runId + ":redisson-lock");
        try {
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            TimeUnit.MILLISECONDS.sleep(4_500L);
            assertTrue(lock.isLocked(), "watchdog must keep the lock alive beyond its initial timeout");
            assertTrue(lock.remainTimeToLive() > 500L, "renewed lock must retain a positive TTL");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            assertFalse(lock.isLocked(), "unlock must remove the Redis lock");
            client.shutdown();
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + name);
        }
        return value;
    }
}
