package com.qilu.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.ai.agent.AiAgentEndpointRegistry;
import com.qilu.ai.api.dto.KnowledgeReloadRequest;
import com.qilu.ai.api.dto.KnowledgeReloadResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiKnowledgeServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extendedResponseKeepsLegacyJsonCompatible() throws Exception {
        KnowledgeReloadResponse legacy = objectMapper.readValue(
                "{\"success\":true,\"documentCount\":2,\"knowledgeVersion\":\"v1\"}",
                KnowledgeReloadResponse.class);
        assertThat(legacy.getSuccess()).isTrue();
        assertThat(legacy.getDocumentCount()).isEqualTo(2);
        assertThat(legacy.getActivated()).isNull();

        String currentJson = "{\"success\":true,\"activated\":true,\"degraded\":false,"
                + "\"sourceDocumentCount\":2,\"chunkCount\":4,\"knowledgeVersion\":\"v2\","
                + "\"indexVersion\":\"idx2\",\"activeKnowledgeVersion\":\"v2\","
                + "\"activeIndexVersion\":\"idx2\",\"backendStates\":{\"bm25\":\"READY\"},"
                + "\"candidateCollection\":\"sha256:0123456789ab\",\"instanceId\":\"agent-1\"}";
        KnowledgeReloadResponse current = objectMapper.readValue(currentJson, KnowledgeReloadResponse.class);
        assertThat(current.getActivated()).isTrue();
        assertThat(current.getChunkCount()).isEqualTo(4);
        assertThat(current.getBackendStates()).containsEntry("bm25", "READY");
    }

    @Test
    void rcU36PartialInstanceFailureIsReportedWithVersionDifference() {
        AiAgentEndpointRegistry endpoints = endpoints("http://agent-1", "http://agent-2");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(eq("http://agent-1/agent/knowledge/reload"), any(),
                eq(KnowledgeReloadResponse.class))).thenReturn(success("agent-1", true));
        when(restTemplate.postForObject(eq("http://agent-2/agent/knowledge/reload"), any(),
                eq(KnowledgeReloadResponse.class))).thenReturn(failedOldActive("agent-2"));

        KnowledgeReloadResponse aggregate = service(endpoints, restTemplate)
                .reloadKnowledge(request("knowledge-v2"));

        assertThat(aggregate.getSuccess()).isFalse();
        assertThat(aggregate.getActivated()).isFalse();
        assertThat(aggregate.getSyncedInstanceCount()).isEqualTo(1);
        assertThat(aggregate.getErrorCode()).isEqualTo("RAG_INSTANCE_VERSION_DIVERGENCE");
        assertThat(aggregate.getInstanceResults()).hasSize(2);
        assertThat(aggregate.getInstanceResults().get(0).getActiveIndexVersion()).isEqualTo("index-v2");
        assertThat(aggregate.getInstanceResults().get(1).getActiveIndexVersion()).isEqualTo("index-v1");
        assertThat(aggregate.getMessage()).doesNotContain("http://agent-1", "http://agent-2");
    }

    @Test
    void idempotentInstancesAtSameActiveVersionAggregateAsSuccess() {
        AiAgentEndpointRegistry endpoints = endpoints("http://agent-1", "http://agent-2");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(eq("http://agent-1/agent/knowledge/reload"), any(),
                eq(KnowledgeReloadResponse.class))).thenReturn(success("agent-1", true));
        when(restTemplate.postForObject(eq("http://agent-2/agent/knowledge/reload"), any(),
                eq(KnowledgeReloadResponse.class))).thenReturn(success("agent-2", false));

        KnowledgeReloadResponse aggregate = service(endpoints, restTemplate)
                .reloadKnowledge(request("knowledge-v2"));

        assertThat(aggregate.getSuccess()).isTrue();
        assertThat(aggregate.getActivated()).isTrue();
        assertThat(aggregate.getSyncedInstanceCount()).isEqualTo(2);
        assertThat(aggregate.getActiveKnowledgeVersion()).isEqualTo("knowledge-v2");
        assertThat(aggregate.getActiveIndexVersion()).isEqualTo("index-v2");
        assertThat(aggregate.getErrorCode()).isNull();
    }

    @Test
    void transportFailureUsesStableInstanceErrorCode() {
        AiAgentEndpointRegistry endpoints = endpoints("http://agent-1");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(eq("http://agent-1/agent/knowledge/reload"), any(),
                eq(KnowledgeReloadResponse.class))).thenThrow(new ResourceAccessException("connection refused"));

        KnowledgeReloadResponse aggregate = service(endpoints, restTemplate)
                .reloadKnowledge(request("knowledge-v2"));

        assertThat(aggregate.getSuccess()).isFalse();
        assertThat(aggregate.getErrorCode()).isEqualTo("RAG_ALL_INSTANCES_FAILED");
        assertThat(aggregate.getInstanceResults()).singleElement()
                .extracting("errorCode").isEqualTo("RAG_AGENT_UNAVAILABLE");
    }

    private AiKnowledgeServiceImpl service(AiAgentEndpointRegistry endpoints, RestTemplate restTemplate) {
        return new AiKnowledgeServiceImpl(endpoints, restTemplate);
    }

    private AiAgentEndpointRegistry endpoints(String... urls) {
        AiAgentEndpointRegistry endpoints = mock(AiAgentEndpointRegistry.class);
        when(endpoints.baseUrls()).thenReturn(Arrays.asList(urls));
        return endpoints;
    }

    private KnowledgeReloadRequest request(String version) {
        KnowledgeReloadRequest request = new KnowledgeReloadRequest();
        request.setKnowledgeVersion(version);
        request.setDocuments(Collections.emptyList());
        return request;
    }

    private KnowledgeReloadResponse success(String instanceId, boolean activated) {
        KnowledgeReloadResponse response = new KnowledgeReloadResponse();
        response.setSuccess(true);
        response.setActivated(activated);
        response.setDegraded(false);
        response.setSourceDocumentCount(2);
        response.setChunkCount(4);
        response.setKnowledgeVersion("knowledge-v2");
        response.setIndexVersion("index-v2");
        response.setActiveKnowledgeVersion("knowledge-v2");
        response.setActiveIndexVersion("index-v2");
        response.setBackendStates(readyBackends());
        response.setInstanceId(instanceId);
        return response;
    }

    private KnowledgeReloadResponse failedOldActive(String instanceId) {
        KnowledgeReloadResponse response = new KnowledgeReloadResponse();
        response.setSuccess(false);
        response.setActivated(false);
        response.setDegraded(false);
        response.setSourceDocumentCount(2);
        response.setChunkCount(3);
        response.setKnowledgeVersion("knowledge-v2");
        response.setIndexVersion("index-v2");
        response.setActiveKnowledgeVersion("knowledge-v1");
        response.setActiveIndexVersion("index-v1");
        response.setBackendStates(readyBackends());
        response.setErrorCode("RAG_REQUIRED_BACKEND_FAILED");
        response.setInstanceId(instanceId);
        return response;
    }

    private Map<String, String> readyBackends() {
        Map<String, String> states = new LinkedHashMap<String, String>();
        states.put("bm25", "READY");
        states.put("faiss", "SKIPPED");
        states.put("milvus", "READY");
        return states;
    }
}
