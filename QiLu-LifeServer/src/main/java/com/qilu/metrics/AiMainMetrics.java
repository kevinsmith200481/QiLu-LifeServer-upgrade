package com.qilu.metrics;

import com.qilu.ai.api.dto.CampusAssistantResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 主服务按返回契约原值统计错误码，确保 Prometheus 与响应/日志口径一致。 */
@Component
public class AiMainMetrics {

    private final ConcurrentHashMap<String, AtomicLong> responses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> memoryBuilds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> memorySummaryUpdates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> memoryRebuilds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> memoryConflicts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> memoryDegraded = new ConcurrentHashMap<>();
    private final AtomicLong memoryRecentTurnCount = new AtomicLong();
    private final AtomicLong memoryEstimatedTokens = new AtomicLong();
    private final AtomicLong memoryTruncated = new AtomicLong();

    public void record(CampusAssistantResponse response) {
        String serviceStage = value(response == null ? null : response.getServiceStage(), "main");
        String errorStage = value(response == null ? null : response.getErrorStage(), "none");
        String errorCode = value(response == null ? null : response.getErrorCode(), "NONE");
        responses.computeIfAbsent(serviceStage + "|" + errorStage + "|" + errorCode,
                ignored -> new AtomicLong()).incrementAndGet();
    }

    public void recordMemoryBuild(String mode, String result, int recentTurns, int estimatedTokens,
                                  boolean truncated) {
        increment(memoryBuilds, value(mode, "legacy") + "|" + value(result, "unknown"));
        memoryRecentTurnCount.set(Math.max(0, recentTurns));
        memoryEstimatedTokens.set(Math.max(0, estimatedTokens));
        if (truncated) {
            memoryTruncated.incrementAndGet();
        }
    }

    public void recordMemorySummary(String source, String result) {
        increment(memorySummaryUpdates, value(source, "deterministic") + "|" + value(result, "unknown"));
    }

    public void recordMemoryRebuild(String reason) {
        increment(memoryRebuilds, value(reason, "unknown"));
    }

    public void recordMemoryConflict(String result) {
        increment(memoryConflicts, value(result, "unknown"));
    }

    public void recordMemoryDegraded(String reason) {
        increment(memoryDegraded, value(reason, "unknown"));
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        responses.forEach((key, count) -> result.put(key, count.get()));
        memorySummaryUpdates.forEach((key, count) -> result.put("memory.summary|" + key, count.get()));
        memoryRebuilds.forEach((key, count) -> result.put("memory.rebuild|" + key, count.get()));
        memoryConflicts.forEach((key, count) -> result.put("memory.conflict|" + key, count.get()));
        memoryDegraded.forEach((key, count) -> result.put("memory.degraded|" + key, count.get()));
        return result;
    }

    public String prometheus() {
        StringBuilder output = new StringBuilder();
        output.append("# HELP qilu_ai_main_response_total Main service AI responses by stable failure contract.\n");
        output.append("# TYPE qilu_ai_main_response_total counter\n");
        responses.forEach((key, count) -> {
            String[] labels = key.split("\\|", -1);
            output.append("qilu_ai_main_response_total{service_stage=\"")
                    .append(label(labels[0])).append("\",error_stage=\"")
                    .append(label(labels[1])).append("\",error_code=\"")
                    .append(label(labels[2])).append("\"} ")
                    .append(count.get()).append('\n');
        });
        output.append("# TYPE ai_memory_build_total counter\n");
        memoryBuilds.forEach((key, count) -> appendPairMetric(
                output, "ai_memory_build_total", "mode", "result", key, count.get()));
        output.append("# TYPE ai_memory_recent_turn_count gauge\n")
                .append("ai_memory_recent_turn_count ").append(memoryRecentTurnCount.get()).append('\n');
        output.append("# TYPE ai_memory_estimated_tokens gauge\n")
                .append("ai_memory_estimated_tokens ").append(memoryEstimatedTokens.get()).append('\n');
        output.append("# TYPE ai_memory_truncated_total counter\n")
                .append("ai_memory_truncated_total{reason=\"budget\"} ").append(memoryTruncated.get()).append('\n');
        output.append("# TYPE ai_memory_summary_update_total counter\n");
        memorySummaryUpdates.forEach((key, count) -> appendPairMetric(
                output, "ai_memory_summary_update_total", "source", "result", key, count.get()));
        output.append("# TYPE ai_memory_rebuild_total counter\n");
        memoryRebuilds.forEach((key, count) -> appendSingleMetric(
                output, "ai_memory_rebuild_total", "reason", key, count.get()));
        output.append("# TYPE ai_memory_update_conflict_total counter\n");
        memoryConflicts.forEach((key, count) -> appendSingleMetric(
                output, "ai_memory_update_conflict_total", "result", key, count.get()));
        output.append("# TYPE ai_memory_degraded_total counter\n");
        memoryDegraded.forEach((key, count) -> appendSingleMetric(
                output, "ai_memory_degraded_total", "reason", key, count.get()));
        return output.toString();
    }

    private static void increment(ConcurrentHashMap<String, AtomicLong> counters, String key) {
        counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static void appendPairMetric(StringBuilder output, String metric, String firstLabel,
                                         String secondLabel, String key, long count) {
        String[] values = key.split("\\|", -1);
        output.append(metric).append('{').append(firstLabel).append("=\"")
                .append(label(values[0])).append("\",").append(secondLabel).append("=\"")
                .append(label(values.length > 1 ? values[1] : "unknown")).append("\"} ")
                .append(count).append('\n');
    }

    private static void appendSingleMetric(StringBuilder output, String metric, String labelName,
                                           String value, long count) {
        output.append(metric).append('{').append(labelName).append("=\"")
                .append(label(value)).append("\"} ").append(count).append('\n');
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String label(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
