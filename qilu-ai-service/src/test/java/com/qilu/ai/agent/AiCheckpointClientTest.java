package com.qilu.ai.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCheckpointClientTest {

    @Test
    void sendsAuthenticatedThreadAndUserDeletionRequests() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        AiAgentEndpointRegistry registry = mock(AiAgentEndpointRegistry.class);
        when(registry.baseUrls()).thenReturn(Collections.singletonList("http://127.0.0.1:18001"));
        when(restTemplate.postForObject(
                eq("http://127.0.0.1:18001/internal/checkpoints/delete"),
                org.mockito.ArgumentMatchers.<HttpEntity<Map<String, Object>>>any(),
                eq(Map.class)))
                .thenReturn(Collections.<String, Object>singletonMap("success", true));
        AiCheckpointClient client = new AiCheckpointClient(restTemplate, registry, "internal-test-token");

        assertThat(client.deleteThread(2006L, "17")).isTrue();
        assertThat(client.deleteUser(2006L)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, org.mockito.Mockito.times(2)).postForObject(
                eq("http://127.0.0.1:18001/internal/checkpoints/delete"),
                captor.capture(),
                eq(Map.class));
        assertThat(captor.getAllValues().get(0).getHeaders().getFirst("X-AI-Internal-Token"))
                .isEqualTo("internal-test-token");
        assertThat(captor.getAllValues().get(0).getBody())
                .containsEntry("userId", 2006L)
                .containsEntry("conversationId", "17");
        assertThat(captor.getAllValues().get(1).getBody())
                .containsOnlyKeys("userId");
    }
}
