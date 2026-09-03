package com.qilu.ai.agent;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-only client for Agent checkpoint lifecycle operations.
 * Chat orchestration stays in the RPC service while internal authentication and
 * the HTTP deletion contract remain isolated in this component.
 */
@Component
public class AiCheckpointClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-AI-Internal-Token";

    private final RestTemplate restTemplate;
    private final AiAgentEndpointRegistry endpointRegistry;
    private final String internalToken;

    public AiCheckpointClient(
            @Qualifier("aiAgentRestTemplate") RestTemplate restTemplate,
            AiAgentEndpointRegistry endpointRegistry,
            @Value("${ai.agent.checkpoint-internal-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.endpointRegistry = endpointRegistry;
        this.internalToken = internalToken;
    }

    public boolean deleteThread(Long userId, String conversationId) {
        if (userId == null || StrUtil.isBlank(conversationId)) {
            throw new IllegalArgumentException("userId and conversationId are required");
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("userId", userId);
        request.put("conversationId", conversationId);
        return delete(request);
    }

    public boolean deleteUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("userId", userId);
        return delete(request);
    }

    private boolean delete(Map<String, Object> request) {
        if (StrUtil.isBlank(internalToken)) {
            throw new IllegalStateException("Agent checkpoint internal token is not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                endpointRegistry.baseUrls().get(0) + "/internal/checkpoints/delete",
                new HttpEntity<Map<String, Object>>(request, headers),
                Map.class);
        return response != null && Boolean.TRUE.equals(response.get("success"));
    }
}
