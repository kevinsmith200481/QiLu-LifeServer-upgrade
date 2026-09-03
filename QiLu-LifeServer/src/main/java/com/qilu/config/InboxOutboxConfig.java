package com.qilu.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(InboxOutboxProperties.class)
public class InboxOutboxConfig {

    @Bean(name = "inboxOutboxExecutor")
    public Executor inboxOutboxExecutor(InboxOutboxProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getImmediateThreads());
        executor.setMaxPoolSize(properties.getImmediateThreads());
        executor.setQueueCapacity(properties.getImmediateQueueCapacity());
        executor.setThreadNamePrefix("inbox-outbox-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
