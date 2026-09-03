package com.qilu.ai.metrics;

import com.qilu.ai.agent.AiAgentEndpointRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiAgentMetricsAggregator {

    private final AiAgentEndpointRegistry endpointRegistry;
    private final RestTemplate agentRestTemplate;

    public AiAgentMetricsAggregator(AiAgentEndpointRegistry endpointRegistry,
                                    @Qualifier("aiAgentRestTemplate") RestTemplate agentRestTemplate) {
        this.endpointRegistry = endpointRegistry;
        this.agentRestTemplate = agentRestTemplate;
    }

    public Map<String, Object> snapshot() {
        List<String> baseUrls = endpointRegistry.baseUrls();
        List<Map<String, Object>> instances = new ArrayList<Map<String, Object>>();
        Map<String, AggregatedOperation> operationStats = new LinkedHashMap<String, AggregatedOperation>();
        Map<String, Integer> knowledgeVersions = new LinkedHashMap<String, Integer>();
        Map<String, Integer> indexVersions = new LinkedHashMap<String, Integer>();

        int healthyCount = 0;
        for (String baseUrl : baseUrls) {
            Map<String, Object> instance = fetchInstanceMetrics(baseUrl);
            instances.add(instance);
            if (Boolean.TRUE.equals(instance.get("healthy"))) {
                healthyCount++;
                countValue(knowledgeVersions, firstPresent(
                        instance, "activeKnowledgeVersion", "knowledgeVersion"));
                countValue(indexVersions, firstPresent(
                        instance, "activeIndexVersion", "indexVersion"));
                mergeOperations(operationStats, instance.get("operations"));
            }
        }

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("service", "qilu-ai-service");
        root.put("targetService", "qilu-ai-agent");
        root.put("generatedAtEpochMs", System.currentTimeMillis());
        root.put("instanceCount", baseUrls.size());
        root.put("healthyInstanceCount", healthyCount);
        root.put("unhealthyInstanceCount", baseUrls.size() - healthyCount);
        root.put("knowledgeVersions", knowledgeVersions);
        root.put("indexVersions", indexVersions);
        root.put("knowledgeVersionConsistent", knowledgeVersions.size() <= 1);
        root.put("indexVersionConsistent", indexVersions.size() <= 1);
        root.put("operations", operationSnapshots(operationStats));
        root.put("instances", instances);
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchInstanceMetrics(String baseUrl) {
        Map<String, Object> instance = new LinkedHashMap<String, Object>();
        instance.put("baseUrl", baseUrl);
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> payload = agentRestTemplate.getForObject(baseUrl + "/metrics", Map.class);
            long elapsedMs = System.currentTimeMillis() - start;
            instance.put("healthy", true);
            instance.put("latencyMs", elapsedMs);
            if (payload != null) {
                copyIfPresent(payload, instance, "service");
                copyIfPresent(payload, instance, "mode");
                copyIfPresent(payload, instance, "llmEnabled");
                copyIfPresent(payload, instance, "vectorRagEnabled");
                copyIfPresent(payload, instance, "vectorIndexEnabled");
                copyIfPresent(payload, instance, "vectorIndexPersistent");
                copyIfPresent(payload, instance, "documentCount");
                copyAliasIfPresent(payload, instance, "knowledgeDocumentCount", "documentCount");
                copyIfPresent(payload, instance, "knowledgeDocumentCount");
                copyIfPresent(payload, instance, "knowledgeVersion");
                copyIfPresent(payload, instance, "indexVersion");
                copyIfPresent(payload, instance, "activeKnowledgeVersion");
                copyIfPresent(payload, instance, "activeIndexVersion");
                copyIfPresent(payload, instance, "candidateKnowledgeVersion");
                copyIfPresent(payload, instance, "candidateIndexVersion");
                copyIfPresent(payload, instance, "reloadState");
                copyIfPresent(payload, instance, "degraded");
                copyIfPresent(payload, instance, "degradedBackends");
                copyIfPresent(payload, instance, "backendStates");
                copyIfPresent(payload, instance, "lastReloadErrorCode");
                copyIfPresent(payload, instance, "instanceId");
                copyIfPresent(payload, instance, "uptimeSeconds");
                copyIfPresent(payload, instance, "operations");
            }
        } catch (RuntimeException e) {
            long elapsedMs = System.currentTimeMillis() - start;
            instance.put("healthy", false);
            instance.put("latencyMs", elapsedMs);
            instance.put("error", e.getClass().getSimpleName());
        }
        return instance;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private void copyAliasIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        if (!target.containsKey(targetKey) && source.containsKey(sourceKey)) {
            target.put(targetKey, source.get(sourceKey));
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeOperations(Map<String, AggregatedOperation> target, Object operationsValue) {
        if (!(operationsValue instanceof Map)) {
            return;
        }
        Map<Object, Object> operations = (Map<Object, Object>) operationsValue;
        for (Map.Entry<Object, Object> entry : operations.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map)) {
                continue;
            }
            String operation = String.valueOf(entry.getKey());
            AggregatedOperation aggregate = target.get(operation);
            if (aggregate == null) {
                aggregate = new AggregatedOperation();
                target.put(operation, aggregate);
            }
            aggregate.merge((Map<String, Object>) entry.getValue());
        }
    }

    private Map<String, Object> operationSnapshots(Map<String, AggregatedOperation> operationStats) {
        Map<String, Object> operations = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, AggregatedOperation> entry : operationStats.entrySet()) {
            operations.put(entry.getKey(), entry.getValue().snapshot());
        }
        return operations;
    }

    private void countValue(Map<String, Integer> counter, Object value) {
        if (value == null) {
            return;
        }
        String key = String.valueOf(value);
        if (key.trim().isEmpty()) {
            return;
        }
        Integer current = counter.get(key);
        counter.put(key, current == null ? 1 : current + 1);
    }

    private Object firstPresent(Map<String, Object> values, String first, String second) {
        Object firstValue = values.get(first);
        return firstValue == null ? values.get(second) : firstValue;
    }

    private static class AggregatedOperation {
        private long total;
        private long success;
        private long failure;
        private long fallback;
        private double totalLatencyMs;
        private double maxLatencyMs;
        private long rateLimited;
        private long circuitOpen;
        private long inputTokens;
        private long outputTokens;
        private double estimatedCostUsd;
        private final Map<String, Integer> lastErrors = new LinkedHashMap<String, Integer>();

        void merge(Map<String, Object> stats) {
            total += longValue(stats, "total");
            success += longValue(stats, "success");
            failure += longValue(stats, "failure");
            fallback += longValue(stats, "fallback");
            totalLatencyMs += doubleValue(stats, "totalLatencyMs");
            maxLatencyMs = Math.max(maxLatencyMs, doubleValue(stats, "maxLatencyMs"));
            rateLimited += longValue(stats, "rateLimited");
            circuitOpen += longValue(stats, "circuitOpen");
            inputTokens += longValue(stats, "inputTokens");
            outputTokens += longValue(stats, "outputTokens");
            estimatedCostUsd += doubleValue(stats, "estimatedCostUsd");
            Object lastError = stats.get("lastError");
            if (lastError != null) {
                String key = String.valueOf(lastError);
                Integer current = lastErrors.get(key);
                lastErrors.put(key, current == null ? 1 : current + 1);
            }
        }

        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("total", total);
            result.put("success", success);
            result.put("failure", failure);
            result.put("fallback", fallback);
            result.put("totalLatencyMs", round(totalLatencyMs));
            result.put("avgLatencyMs", total == 0 ? 0.0D : round(totalLatencyMs / total));
            result.put("maxLatencyMs", round(maxLatencyMs));
            result.put("rateLimited", rateLimited);
            result.put("circuitOpen", circuitOpen);
            result.put("inputTokens", inputTokens);
            result.put("outputTokens", outputTokens);
            result.put("estimatedCostUsd", round(estimatedCostUsd));
            result.put("lastErrors", lastErrors);
            return result;
        }

        private static long longValue(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return 0L;
        }

        private static double doubleValue(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            return 0.0D;
        }

        private static double round(double value) {
            return Math.round(value * 100.0D) / 100.0D;
        }
    }
}
