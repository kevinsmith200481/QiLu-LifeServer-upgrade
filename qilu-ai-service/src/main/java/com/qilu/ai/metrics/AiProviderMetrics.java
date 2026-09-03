package com.qilu.ai.metrics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AiProviderMetrics {

    private final long startedAt = System.currentTimeMillis();
    private final ConcurrentHashMap<String, OperationStats> stats = new ConcurrentHashMap<>();

    public void record(String operation, long elapsedMs, boolean success, boolean fallback, Exception error) {
        OperationStats operationStats = stats.computeIfAbsent(operation, key -> new OperationStats());
        operationStats.record(elapsedMs, success, fallback, error);
    }

    public void recordRejected(String operation, String reason) {
        OperationStats operationStats = stats.computeIfAbsent(operation, key -> new OperationStats());
        operationStats.recordRejected(reason);
    }

    public void recordTokenUsage(String operation, long inputTokens, long outputTokens, double estimatedCostUsd) {
        OperationStats operationStats = stats.computeIfAbsent(operation, key -> new OperationStats());
        operationStats.recordTokenUsage(inputTokens, outputTokens, estimatedCostUsd);
    }

    public void recordFailureCode(String operation, String errorCode) {
        stats.computeIfAbsent(operation, key -> new OperationStats()).recordFailureCode(errorCode);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("service", "qilu-ai-service");
        root.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000.0D);
        Map<String, Object> operations = new LinkedHashMap<>();
        stats.forEach((name, operationStats) -> operations.put(name, operationStats.snapshot()));
        root.put("operations", operations);
        return root;
    }

    public String prometheus() {
        StringBuilder builder = new StringBuilder();
        builder.append("# HELP qilu_ai_provider_operation_total Total Java AI Provider operation calls.\n");
        builder.append("# TYPE qilu_ai_provider_operation_total counter\n");
        stats.forEach((name, operationStats) -> operationStats.appendPrometheus(builder, name));
        builder.append("qilu_ai_provider_uptime_seconds ")
                .append((System.currentTimeMillis() - startedAt) / 1000.0D)
                .append('\n');
        return builder.toString();
    }

    private static class OperationStats {
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong success = new AtomicLong();
        private final AtomicLong failure = new AtomicLong();
        private final AtomicLong fallback = new AtomicLong();
        private final AtomicLong totalLatencyMs = new AtomicLong();
        private final AtomicLong maxLatencyMs = new AtomicLong();
        private final AtomicLong rateLimited = new AtomicLong();
        private final AtomicLong circuitOpen = new AtomicLong();
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final DoubleAdder estimatedCostUsd = new DoubleAdder();
        private final ConcurrentHashMap<String, AtomicLong> failureCodes = new ConcurrentHashMap<String, AtomicLong>();
        private final List<Long> latencySamplesMs = Collections.synchronizedList(new ArrayList<Long>());
        private volatile long lastLatencyMs;
        private volatile String lastError;

        void record(long elapsedMs, boolean successValue, boolean fallbackValue, Exception error) {
            total.incrementAndGet();
            if (successValue) {
                success.incrementAndGet();
            } else {
                failure.incrementAndGet();
            }
            if (fallbackValue) {
                fallback.incrementAndGet();
            }
            totalLatencyMs.addAndGet(Math.max(0L, elapsedMs));
            maxLatencyMs.accumulateAndGet(Math.max(0L, elapsedMs), Math::max);
            lastLatencyMs = Math.max(0L, elapsedMs);
            lastError = error == null ? null : error.getClass().getSimpleName();
            synchronized (latencySamplesMs) {
                latencySamplesMs.add(Math.max(0L, elapsedMs));
                if (latencySamplesMs.size() > 200) {
                    latencySamplesMs.subList(0, latencySamplesMs.size() - 200).clear();
                }
            }
        }

        void recordRejected(String reason) {
            total.incrementAndGet();
            failure.incrementAndGet();
            fallback.incrementAndGet();
            if ("RATE_LIMITED".equals(reason)) {
                rateLimited.incrementAndGet();
            }
            if ("CIRCUIT_OPEN".equals(reason)) {
                circuitOpen.incrementAndGet();
            }
            lastError = reason;
            recordFailureCode(reason);
        }

        void recordFailureCode(String errorCode) {
            if (errorCode != null && !errorCode.trim().isEmpty()) {
                failureCodes.computeIfAbsent(errorCode, key -> new AtomicLong()).incrementAndGet();
                lastError = errorCode;
            }
        }

        void recordTokenUsage(long inputTokensValue, long outputTokensValue, double estimatedCostUsdValue) {
            inputTokens.addAndGet(Math.max(0L, inputTokensValue));
            outputTokens.addAndGet(Math.max(0L, outputTokensValue));
            estimatedCostUsd.add(Math.max(0.0D, estimatedCostUsdValue));
        }

        Map<String, Object> snapshot() {
            long totalValue = total.get();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total", totalValue);
            map.put("success", success.get());
            map.put("failure", failure.get());
            map.put("fallback", fallback.get());
            map.put("totalLatencyMs", totalLatencyMs.get());
            map.put("avgLatencyMs", totalValue == 0 ? 0.0D : totalLatencyMs.get() * 1.0D / totalValue);
            map.put("p95LatencyMs", percentileLatency(0.95D));
            map.put("maxLatencyMs", maxLatencyMs.get());
            map.put("lastLatencyMs", lastLatencyMs);
            map.put("lastError", lastError);
            map.put("rateLimited", rateLimited.get());
            map.put("circuitOpen", circuitOpen.get());
            map.put("inputTokens", inputTokens.get());
            map.put("outputTokens", outputTokens.get());
            map.put("estimatedCostUsd", estimatedCostUsd.sum());
            Map<String, Long> errorCounts = new LinkedHashMap<String, Long>();
            failureCodes.forEach((code, count) -> errorCounts.put(code, count.get()));
            map.put("failureCodes", errorCounts);
            return map;
        }

        void appendPrometheus(StringBuilder builder, String operation) {
            String label = labelValue(operation);
            builder.append("qilu_ai_provider_operation_total{operation=\"").append(label).append("\"} ").append(total.get()).append('\n');
            builder.append("qilu_ai_provider_operation_success_total{operation=\"").append(label).append("\"} ").append(success.get()).append('\n');
            builder.append("qilu_ai_provider_operation_failure_total{operation=\"").append(label).append("\"} ").append(failure.get()).append('\n');
            builder.append("qilu_ai_provider_operation_fallback_total{operation=\"").append(label).append("\"} ").append(fallback.get()).append('\n');
            builder.append("qilu_ai_provider_operation_latency_avg_ms{operation=\"").append(label).append("\"} ")
                    .append(total.get() == 0 ? 0.0D : totalLatencyMs.get() * 1.0D / total.get())
                    .append('\n');
            builder.append("qilu_ai_provider_operation_latency_p95_ms{operation=\"").append(label).append("\"} ")
                    .append(percentileLatency(0.95D))
                    .append('\n');
            builder.append("qilu_ai_provider_operation_latency_max_ms{operation=\"").append(label).append("\"} ").append(maxLatencyMs.get()).append('\n');
            failureCodes.forEach((code, count) -> builder
                    .append("qilu_ai_provider_failure_code_total{operation=\"").append(label)
                    .append("\",error_code=\"").append(labelValue(code)).append("\"} ")
                    .append(count.get()).append('\n'));
        }

        private long percentileLatency(double percentile) {
            synchronized (latencySamplesMs) {
                if (latencySamplesMs.isEmpty()) {
                    return 0L;
                }
                List<Long> copy = new ArrayList<Long>(latencySamplesMs);
                Collections.sort(copy);
                int index = (int) ((copy.size() - 1) * percentile);
                return copy.get(index);
            }
        }

        private static String labelValue(String value) {
            return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
