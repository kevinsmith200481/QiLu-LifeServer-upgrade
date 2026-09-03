package com.qilu.ai.service.impl;

import com.qilu.ai.acceptance.AcceptanceFaultInjector;
import com.qilu.ai.agent.AiAgentEndpointRegistry;
import com.qilu.ai.agent.AiCheckpointClient;
import com.qilu.ai.agent.AiMemorySummaryClient;
import com.qilu.ai.api.dto.CampusAssistantRequest;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.ai.api.dto.CampusMemoryDTO;
import com.qilu.ai.api.dto.CampusMemoryEntitiesDTO;
import com.qilu.ai.api.dto.CampusMemoryEntityDTO;
import com.qilu.ai.api.dto.CampusMemoryTurnDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryRequestDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryResponseDTO;
import com.qilu.ai.api.dto.CampusServicePointDTO;
import com.qilu.ai.governance.AiGovernanceManager;
import com.qilu.ai.governance.AiGovernanceProperties;
import com.qilu.ai.metrics.AiProviderMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class AiCampusAssistantServiceImplTest {

    @Test
    void distinguishesAgentReadTimeoutAndReturnsFallbackRecord() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenThrow(new ResourceAccessException("read failed",
                        new SocketTimeoutException("Read timed out")));
        CampusAssistantResponse response = service(restTemplate, new AiGovernanceProperties()).chat(request());

        assertThat(response.getErrorCode()).isEqualTo("AGENT_READ_TIMEOUT");
        assertThat(response.getErrorStage()).isEqualTo("agent_http_read");
        assertThat(response.getRetriable()).isTrue();
        assertThat(response.getFallbackReason()).isEqualTo("AGENT_READ_TIMEOUT");
        assertThat(response.getFallbackRecords()).hasSize(1);
    }

    @Test
    void distinguishesAgentHttp500AndInvalidJson() {
        RestTemplate http500 = mock(RestTemplate.class);
        when(http500.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(service(http500, new AiGovernanceProperties()).chat(request()).getErrorCode())
                .isEqualTo("AGENT_HTTP_ERROR");

        RestTemplate invalidJson = mock(RestTemplate.class);
        when(invalidJson.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenThrow(new RestClientException("JSON decode failed"));
        assertThat(service(invalidJson, new AiGovernanceProperties()).chat(request()).getErrorCode())
                .isEqualTo("AGENT_INVALID_RESPONSE");
    }

    @Test
    void preservesCheckpointThreadConflictAcrossProviderBoundary() {
        RestTemplate conflict = mock(RestTemplate.class);
        when(conflict.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.CONFLICT));

        CampusAssistantResponse response = service(
                conflict, new AiGovernanceProperties()).chat(request());

        assertThat(response.getErrorCode()).isEqualTo("CHECKPOINT_THREAD_CONFLICT");
        assertThat(response.getErrorStage()).isEqualTo("checkpoint_concurrency");
        assertThat(response.getRetriable()).isTrue();
    }

    @Test
    void rateLimitAndCircuitOpenHaveDifferentCodes() {
        RestTemplate success = mock(RestTemplate.class);
        when(success.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenReturn(successResponse());
        AiGovernanceProperties rateProperties = new AiGovernanceProperties();
        rateProperties.setRateLimitPerMinute(1);
        AiCampusAssistantServiceImpl rateLimited = service(success, rateProperties);
        assertThat(rateLimited.chat(request()).getErrorCode()).isNull();
        assertThat(rateLimited.chat(request()).getErrorCode()).isEqualTo("RATE_LIMITED");

        RestTemplate failed = mock(RestTemplate.class);
        when(failed.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenThrow(new ResourceAccessException("connection refused"));
        AiGovernanceProperties circuitProperties = new AiGovernanceProperties();
        circuitProperties.setCircuitFailureThreshold(1);
        AiCampusAssistantServiceImpl circuit = service(failed, circuitProperties);
        assertThat(circuit.chat(request()).getErrorCode()).isEqualTo("AGENT_UNAVAILABLE");
        assertThat(circuit.chat(request()).getErrorCode()).isEqualTo("CIRCUIT_OPEN");
    }

    @Test
    void delegatesCheckpointLifecycleToProviderOnlyClient() {
        AiCheckpointClient checkpointClient = mock(AiCheckpointClient.class);
        when(checkpointClient.deleteThread(2006L, "17")).thenReturn(true);
        when(checkpointClient.deleteUser(2006L)).thenReturn(true);
        AiCampusAssistantServiceImpl service = service(
                mock(RestTemplate.class), new AiGovernanceProperties(), checkpointClient);

        assertThat(service.deleteCheckpoint(2006L, "17")).isTrue();
        assertThat(service.deleteUserCheckpoints(2006L)).isTrue();
        verify(checkpointClient).deleteThread(2006L, "17");
        verify(checkpointClient).deleteUser(2006L);
    }

    @Test
    void delegatesMemorySummaryAndMapsTimeoutToStableFailure() {
        AiMemorySummaryClient successClient = mock(AiMemorySummaryClient.class);
        CampusMemorySummaryResponseDTO success = new CampusMemorySummaryResponseDTO();
        success.setSuccess(true);
        success.setRollingSummary("bounded summary");
        CampusMemorySummaryRequestDTO request = new CampusMemorySummaryRequestDTO();
        when(successClient.summarize(request)).thenReturn(success);

        CampusMemorySummaryResponseDTO result = service(
                mock(RestTemplate.class), new AiGovernanceProperties(),
                mock(AiCheckpointClient.class), successClient).summarizeMemory(request);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getRollingSummary()).isEqualTo("bounded summary");

        AiMemorySummaryClient timeoutClient = mock(AiMemorySummaryClient.class);
        when(timeoutClient.summarize(request)).thenThrow(new ResourceAccessException(
                "read failed", new SocketTimeoutException("Read timed out")));
        CampusMemorySummaryResponseDTO timeout = service(
                mock(RestTemplate.class), new AiGovernanceProperties(),
                mock(AiCheckpointClient.class), timeoutClient).summarizeMemory(request);
        assertThat(timeout.getSuccess()).isFalse();
        assertThat(timeout.getErrorCode()).isEqualTo("SUMMARY_TIMEOUT");
    }

    @Test
    void commentAggregateQuestionUsesLivePointCountsWhenAgentIsUnavailable() {
        RestTemplate unavailable = mock(RestTemplate.class);
        when(unavailable.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenThrow(new ResourceAccessException("connection refused"));
        CampusAssistantRequest request = request();
        request.setQuestion("哪个网点有留言");
        request.setServicePoints(Arrays.asList(
                point(1L, "维修中心", 2),
                point(2L, "打印点", 0),
                point(3L, "快递站", 5)));

        CampusAssistantResponse response = service(unavailable, new AiGovernanceProperties()).chat(request);

        assertThat(response.getIntent()).isEqualTo("service_point_comment_ranking");
        assertThat(response.getAnswer()).contains("有留言的网点共 2 个", "快递站 5条", "维修中心 2条");
        assertThat(response.getAnswer()).doesNotContain("打印点");
    }

    @Test
    void forwardsMemoryObjectWithoutChangingItsContent() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenReturn(successResponse());
        CampusAssistantRequest request = request();
        request.setMemory(memory());

        service(restTemplate, new AiGovernanceProperties()).chat(request);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(any(String.class), entity.capture(), eq(CampusAssistantResponse.class));
        CampusAssistantRequest forwarded = (CampusAssistantRequest) entity.getValue().getBody();
        assertThat(forwarded).isSameAs(request);
        assertThat(forwarded.getMemory()).isSameAs(request.getMemory());
        assertThat(forwarded.getMemory().getEntities().getTickets()).extracting("id").containsExactly(12L);
        assertThat(forwarded.getMemory().getRollingSummary()).isEqualTo("bounded summary");
    }

    @Test
    void providerFallbackReturnsBodyFreeMemoryDiagnostics() {
        RestTemplate unavailable = mock(RestTemplate.class);
        when(unavailable.postForObject(any(String.class), any(), eq(CampusAssistantResponse.class)))
                .thenThrow(new ResourceAccessException("connection refused"));
        CampusAssistantRequest request = request();
        request.setMemory(memory());

        CampusAssistantResponse response = service(
                unavailable,
                new AiGovernanceProperties()).chat(request);

        assertThat(response.getMemoryDiagnostics()).isNotNull();
        assertThat(response.getMemoryDiagnostics().getEntityTypes()).containsExactly("ticket");
        assertThat(response.getMemoryDiagnostics().getDegraded()).isTrue();
        assertThat(response.getMemoryDiagnostics().getDegradedReason()).isEqualTo("AGENT_UNAVAILABLE");
    }

    @Test
    void rejectsOversizedMemoryBeforeCallingAgent() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CampusAssistantRequest request = request();
        CampusMemoryDTO memory = memory();
        memory.setRollingSummary(String.join("", Collections.nCopies(70_000, "x")));
        request.setMemory(memory);

        CampusAssistantResponse response = service(
                restTemplate,
                new AiGovernanceProperties()).chat(request);

        assertThat(response.getErrorCode()).isEqualTo("MEMORY_PAYLOAD_TOO_LARGE");
        assertThat(response.getMemoryDiagnostics().getDegradedReason())
                .isEqualTo("MEMORY_PAYLOAD_TOO_LARGE");
        verify(restTemplate, never()).postForObject(any(String.class), any(), eq(CampusAssistantResponse.class));
    }

    private AiCampusAssistantServiceImpl service(RestTemplate restTemplate, AiGovernanceProperties properties) {
        return service(restTemplate, properties, mock(AiCheckpointClient.class));
    }

    private AiCampusAssistantServiceImpl service(RestTemplate restTemplate,
                                                 AiGovernanceProperties properties,
                                                 AiCheckpointClient checkpointClient) {
        return service(restTemplate, properties, checkpointClient, mock(AiMemorySummaryClient.class));
    }

    private AiCampusAssistantServiceImpl service(RestTemplate restTemplate,
                                                 AiGovernanceProperties properties,
                                                 AiCheckpointClient checkpointClient,
                                                 AiMemorySummaryClient summaryClient) {
        AiAgentEndpointRegistry endpoints = mock(AiAgentEndpointRegistry.class);
        when(endpoints.baseUrls()).thenReturn(Collections.singletonList("http://127.0.0.1:18001"));
        return new AiCampusAssistantServiceImpl(
                new AiProviderMetrics(),
                new AiGovernanceManager(properties),
                mock(AcceptanceFaultInjector.class),
                restTemplate,
                endpoints,
                checkpointClient,
                summaryClient);
    }

    private CampusAssistantRequest request() {
        CampusAssistantRequest request = new CampusAssistantRequest();
        request.setTraceId("trace-test");
        request.setQuestion("test");
        return request;
    }

    private CampusAssistantResponse successResponse() {
        CampusAssistantResponse response = new CampusAssistantResponse();
        response.setAnswer("ok");
        response.setIntent("general");
        return response;
    }

    private CampusMemoryDTO memory() {
        CampusMemoryEntityDTO ticket = new CampusMemoryEntityDTO();
        ticket.setId(12L);
        ticket.setLastSeenTurnId("turn-1");
        ticket.setLastSeenMessageId(2L);
        CampusMemoryEntitiesDTO entities = new CampusMemoryEntitiesDTO();
        entities.setTickets(Collections.singletonList(ticket));
        entities.setAppointments(Collections.emptyList());
        entities.setServicePoints(Collections.emptyList());
        CampusMemoryTurnDTO turn = new CampusMemoryTurnDTO();
        turn.setTurnId("turn-1");
        turn.setQuestion("question");
        turn.setAnswer("answer");
        turn.setIntent("ticket_status");
        CampusMemoryDTO memory = new CampusMemoryDTO();
        memory.setMode("legacy");
        memory.setSchemaVersion("2");
        memory.setConversationId("991001");
        memory.setRecentTurns(Collections.singletonList(turn));
        memory.setRollingSummary("bounded summary");
        memory.setEntities(entities);
        memory.setLastProcessedMessageId(2L);
        memory.setSummaryVersion(1L);
        memory.setTruncated(false);
        memory.setEstimatedTokens(32);
        return memory;
    }

    private CampusServicePointDTO point(Long id, String name, int commentCount) {
        CampusServicePointDTO point = new CampusServicePointDTO();
        point.setId(id);
        point.setName(name);
        point.setCommentCount(commentCount);
        return point;
    }
}
