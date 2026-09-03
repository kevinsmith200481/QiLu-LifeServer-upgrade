package com.qilu.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 阶段 E 摘要线程池：固定容量、拒绝即降级，绝不在请求线程执行模型调用。 */
@Configuration
public class AiMemoryConfig {

    @Bean(name = "aiMemorySummaryExecutor")
    public Executor aiMemorySummaryExecutor(AiMemoryProperties properties) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getSummarizerThreads());
        executor.setMaxPoolSize(properties.getSummarizerThreads());
        executor.setQueueCapacity(properties.getSummarizerQueueCapacity());
        executor.setThreadNamePrefix("ai-memory-summary-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(2);
        executor.initialize();
        return executor;
    }
}
