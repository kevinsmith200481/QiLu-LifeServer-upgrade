package com.qilu.ai.controller;

import com.qilu.ai.metrics.AiAgentMetricsAggregator;
import com.qilu.ai.metrics.AiProviderMetrics;
import com.qilu.ai.metrics.AiAgentHttpClientMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AiProviderMetricsController {

    private final AiProviderMetrics metrics;
    private final AiAgentMetricsAggregator agentMetricsAggregator;
    private final AiAgentHttpClientMetrics httpClientMetrics;

    public AiProviderMetricsController(AiProviderMetrics metrics,
                                       AiAgentMetricsAggregator agentMetricsAggregator,
                                       AiAgentHttpClientMetrics httpClientMetrics) {
        this.metrics = metrics;
        this.agentMetricsAggregator = agentMetricsAggregator;
        this.httpClientMetrics = httpClientMetrics;
    }

    @GetMapping("/ai-provider/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    @GetMapping(value = "/ai-provider/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String prometheus() {
        return metrics.prometheus();
    }

    @GetMapping("/ai-provider/agent-metrics")
    public Map<String, Object> agentMetrics() {
        return agentMetricsAggregator.snapshot();
    }

    @GetMapping("/ai-provider/metrics/aggregate")
    public Map<String, Object> aggregateMetrics() {
        Map<String, Object> aggregate = new LinkedHashMap<String, Object>();
        aggregate.put("provider", metrics.snapshot());
        aggregate.put("agents", agentMetricsAggregator.snapshot());
        return aggregate;
    }

    @GetMapping("/ai-provider/resources")
    public Map<String, Object> resources() {
        return httpClientMetrics.snapshot();
    }
}
