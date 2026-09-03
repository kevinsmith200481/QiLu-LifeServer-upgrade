package com.qilu.ai.governance;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AiGovernanceManager {

    private final AiGovernanceProperties properties;
    private final ConcurrentHashMap<String, OperationGuard> guards = new ConcurrentHashMap<String, OperationGuard>();

    public AiGovernanceManager(AiGovernanceProperties properties) {
        this.properties = properties;
    }

    public AiCallPermit tryAcquire(String operation) {
        OperationGuard guard = guards.computeIfAbsent(operation, key -> new OperationGuard());
        long now = System.currentTimeMillis();
        if (guard.isCircuitOpen(now, properties.getCircuitOpenMillis())) {
            return AiCallPermit.rejected("CIRCUIT_OPEN");
        }
        if (!guard.tryAcquireRate(now, properties.getRateLimitPerMinute())) {
            return AiCallPermit.rejected("RATE_LIMITED");
        }
        return AiCallPermit.allowed();
    }

    public void recordSuccess(String operation) {
        guards.computeIfAbsent(operation, key -> new OperationGuard()).recordSuccess();
    }

    public void recordFailure(String operation) {
        guards.computeIfAbsent(operation, key -> new OperationGuard())
                .recordFailure(properties.getCircuitFailureThreshold());
    }

    public AiTokenCost estimateCost(String inputText, String outputText) {
        long inputTokens = estimateTokens(inputText);
        long outputTokens = estimateTokens(outputText);
        double cost = inputTokens * properties.getInputPricePer1kTokensUsd() / 1000.0D
                + outputTokens * properties.getOutputPricePer1kTokensUsd() / 1000.0D;
        return new AiTokenCost(inputTokens, outputTokens, cost);
    }

    private long estimateTokens(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil(text.length() / 4.0D));
    }

    private static class OperationGuard {
        private final AtomicInteger callsInWindow = new AtomicInteger();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long windowStartMs = System.currentTimeMillis();
        private volatile long circuitOpenedAtMs = -1L;

        synchronized boolean tryAcquireRate(long now, int limitPerMinute) {
            if (limitPerMinute <= 0) {
                return true;
            }
            if (now - windowStartMs >= 60000L) {
                windowStartMs = now;
                callsInWindow.set(0);
            }
            return callsInWindow.incrementAndGet() <= limitPerMinute;
        }

        boolean isCircuitOpen(long now, long openMillis) {
            long openedAt = circuitOpenedAtMs;
            if (openedAt < 0) {
                return false;
            }
            if (now - openedAt < openMillis) {
                return true;
            }
            circuitOpenedAtMs = -1L;
            consecutiveFailures.set(0);
            return false;
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            circuitOpenedAtMs = -1L;
        }

        void recordFailure(int threshold) {
            if (threshold > 0 && consecutiveFailures.incrementAndGet() >= threshold) {
                circuitOpenedAtMs = System.currentTimeMillis();
            }
        }
    }
}
