package com.qilu.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCallExecutorTest {

    @Test
    void timeoutCancelsWorkerAndKeepsPoolBounded() throws Exception {
        AiCallProperties properties = new AiCallProperties();
        properties.setHttpTotalTimeoutMs(200L);
        properties.setRpcTimeoutMs(50L);
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(1);
        AiCallExecutor executor = new AiCallExecutor(properties);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        try {
            assertThatThrownBy(() -> executor.execute(() -> {
                try {
                    TimeUnit.SECONDS.sleep(5L);
                } catch (InterruptedException error) {
                    interrupted.set(true);
                    throw error;
                }
                return "late";
            })).isInstanceOf(TimeoutException.class);

            for (int i = 0; i < 20 && !interrupted.get(); i++) {
                TimeUnit.MILLISECONDS.sleep(10L);
            }
            assertThat(interrupted).isTrue();
            assertThat(executor.poolSize()).isLessThanOrEqualTo(1);
            assertThat(executor.queueSize()).isZero();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void tenTimeoutsDoNotGrowPoolOrQueue() throws Exception {
        AiCallProperties properties = new AiCallProperties();
        properties.setHttpTotalTimeoutMs(100L);
        properties.setRpcTimeoutMs(5L);
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(1);
        AiCallExecutor executor = new AiCallExecutor(properties);
        try {
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() -> executor.execute(() -> {
                    TimeUnit.SECONDS.sleep(1L);
                    return null;
                })).isInstanceOf(TimeoutException.class);
            }
            TimeUnit.MILLISECONDS.sleep(50L);
            assertThat(executor.poolSize()).isLessThanOrEqualTo(1);
            assertThat(executor.activeCount()).isZero();
            assertThat(executor.queueSize()).isZero();
        } finally {
            executor.shutdown();
        }
    }
}
