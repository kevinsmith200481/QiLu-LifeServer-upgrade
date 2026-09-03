package com.qilu.ai.agent;

import cn.hutool.core.util.StrUtil;
import com.qilu.ai.api.dto.CampusMemorySummaryRequestDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryResponseDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Provider 内部摘要客户端；鉴权头与响应正文都不会写入日志。 */
@Component
public class AiMemorySummaryClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-AI-Internal-Token";

    private final RestTemplate restTemplate;
    private final AiAgentEndpointRegistry endpointRegistry;
    private final String internalToken;

    public AiMemorySummaryClient(
            @Qualifier("aiAgentRestTemplate") RestTemplate restTemplate,
            AiAgentEndpointRegistry endpointRegistry,
            @Value("${ai.agent.checkpoint-internal-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.endpointRegistry = endpointRegistry;
        this.internalToken = internalToken;
    }

    public CampusMemorySummaryResponseDTO summarize(CampusMemorySummaryRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("Memory summary request is required");
        }
        if (StrUtil.isBlank(internalToken)) {
            throw new IllegalStateException("Agent internal token is not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        return restTemplate.postForObject(
                endpointRegistry.baseUrls().get(0) + "/internal/memory/summarize",
                new HttpEntity<CampusMemorySummaryRequestDTO>(request, headers),
                CampusMemorySummaryResponseDTO.class);
    }
}
