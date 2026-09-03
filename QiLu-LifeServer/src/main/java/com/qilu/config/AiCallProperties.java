package com.qilu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 主服务 AI 调用预算与隔离舱配置。 */
@Component
@ConfigurationProperties(prefix = "qilu.ai.call")
public class AiCallProperties {

    private long httpTotalTimeoutMs = 55_000L;
    private long rpcTimeoutMs = 52_000L;
    private int corePoolSize = 4;
    private int maxPoolSize = 8;
    private int queueCapacity = 32;

    public long getHttpTotalTimeoutMs() {
        return httpTotalTimeoutMs;
    }

    public void setHttpTotalTimeoutMs(long httpTotalTimeoutMs) {
        this.httpTotalTimeoutMs = httpTotalTimeoutMs;
    }

    public long getRpcTimeoutMs() {
        return rpcTimeoutMs;
    }

    public void setRpcTimeoutMs(long rpcTimeoutMs) {
        this.rpcTimeoutMs = rpcTimeoutMs;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public void validate() {
        if (rpcTimeoutMs <= 0 || httpTotalTimeoutMs <= rpcTimeoutMs) {
            throw new IllegalStateException("AI HTTP total timeout must be greater than the RPC timeout");
        }
        if (corePoolSize <= 0 || maxPoolSize < corePoolSize || queueCapacity <= 0) {
            throw new IllegalStateException("AI executor core/max/queue configuration is invalid");
        }
    }
}
