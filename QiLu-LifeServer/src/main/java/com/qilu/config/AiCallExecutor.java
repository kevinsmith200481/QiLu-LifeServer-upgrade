package com.qilu.config;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 主服务 AI RPC 隔离舱。线程数和排队长度均有上限；超时后同时取消 Future，
 * 并把调用线程的 OpenTelemetry/MDC 上下文传入工作线程，避免异步边界切断 Trace。
 */
@Component
public class AiCallExecutor {

    private final AiCallProperties properties;
    private final ThreadPoolExecutor executor;

    public AiCallExecutor(AiCallProperties properties) {
        properties.validate();
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(
                properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getQueueCapacity()),
                new AiThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
    }

    public <T> T execute(Callable<T> task) throws Exception {
        Context parentContext = Context.current();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Future<T> future = executor.submit(() -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            restoreMdc(mdc);
            try (Scope ignored = parentContext.makeCurrent()) {
                return task.call();
            } finally {
                restoreMdc(previousMdc);
            }
        });
        try {
            return future.get(properties.getRpcTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            // cancel(true) 负责打断调用线程；RPC transport 自身的超时负责最终释放 socket。
            future.cancel(true);
            throw error;
        }
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public int poolSize() {
        return executor.getPoolSize();
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    public AiCallProperties getProperties() {
        return properties;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static void restoreMdc(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(values);
        }
    }

    private static class AiThreadFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "qilu-ai-call-" + (++sequence));
            thread.setDaemon(true);
            return thread;
        }
    }
}
