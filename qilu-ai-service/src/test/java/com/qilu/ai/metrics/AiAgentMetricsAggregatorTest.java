package com.qilu.ai.metrics;

import com.qilu.ai.agent.AiAgentEndpointRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAgentMetricsAggregatorTest {

    @Test
    void aggregateStatusReportsKnowledgeAndIndexVersionDifferences() {
        AiAgentEndpointRegistry endpoints = mock(AiAgentEndpointRegistry.class);
        when(endpoints.baseUrls()).thenReturn(Arrays.asList("http://agent-1", "http://agent-2"));
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject("http://agent-1/metrics", Map.class))
                .thenReturn(status("agent-1", "knowledge-v2", "index-v2"));
        when(restTemplate.getForObject("http://agent-2/metrics", Map.class))
                .thenReturn(status("agent-2", "knowledge-v1", "index-v1"));

        Map<String, Object> aggregate = new AiAgentMetricsAggregator(endpoints, restTemplate).snapshot();

        assertThat(aggregate.get("knowledgeVersionConsistent")).isEqualTo(false);
        assertThat(aggregate.get("indexVersionConsistent")).isEqualTo(false);
        Map<?, ?> knowledgeVersions = (Map<?, ?>) aggregate.get("knowledgeVersions");
        Map<?, ?> indexVersions = (Map<?, ?>) aggregate.get("indexVersions");
        assertThat(knowledgeVersions).hasSize(2);
        assertThat(knowledgeVersions.containsKey("knowledge-v1")).isTrue();
        assertThat(knowledgeVersions.containsKey("knowledge-v2")).isTrue();
        assertThat(indexVersions).hasSize(2);
        assertThat(indexVersions.containsKey("index-v1")).isTrue();
        assertThat(indexVersions.containsKey("index-v2")).isTrue();
    }

    private Map<String, Object> status(String instanceId, String knowledgeVersion, String indexVersion) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("instanceId", instanceId);
        status.put("activeKnowledgeVersion", knowledgeVersion);
        status.put("activeIndexVersion", indexVersion);
        status.put("reloadState", "ACTIVE");
        status.put("degraded", false);
        status.put("operations", new LinkedHashMap<String, Object>());
        return status;
    }
}
