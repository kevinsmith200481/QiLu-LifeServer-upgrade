package com.qilu.ai.service.impl;

import cn.hutool.json.JSONUtil;
import com.qilu.ai.api.dto.TicketAiRequest;
import com.qilu.ai.api.dto.TicketCategoryDTO;
import com.qilu.ai.api.dto.TicketSummaryDTO;
import com.qilu.ai.api.service.AiTicketService;
import com.qilu.ai.governance.AiCallPermit;
import com.qilu.ai.governance.AiGovernanceManager;
import com.qilu.ai.governance.AiTokenCost;
import com.qilu.ai.metrics.AiProviderMetrics;
import com.qilu.ai.agent.AiAgentEndpointRegistry;
import gamer.springboot.starter.annotation.RpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestTemplate;

@RpcService(interfaceClass = AiTicketService.class)
public class AiTicketServiceImpl implements AiTicketService {

    private static final Logger log = LoggerFactory.getLogger(AiTicketServiceImpl.class);
    private final AiProviderMetrics metrics;
    private final AiGovernanceManager governanceManager;
    private final RestTemplate agentRestTemplate;
    private final AiAgentEndpointRegistry endpointRegistry;

    public AiTicketServiceImpl(AiProviderMetrics metrics,
                               AiGovernanceManager governanceManager,
                               @Qualifier("aiAgentRestTemplate") RestTemplate agentRestTemplate,
                               AiAgentEndpointRegistry endpointRegistry) {
        this.metrics = metrics;
        this.governanceManager = governanceManager;
        this.agentRestTemplate = agentRestTemplate;
        this.endpointRegistry = endpointRegistry;
    }

    @Override
    public TicketSummaryDTO summarize(TicketAiRequest request) {
        String operation = "ticket.summary";
        long start = System.currentTimeMillis();
        AiCallPermit permit = governanceManager.tryAcquire(operation);
        if (!permit.isAllowed()) {
            metrics.recordRejected(operation, permit.getReason());
            return fallbackSummary(request);
        }
        TicketSummaryDTO response = callAgent("/agent/ticket/summary", request, TicketSummaryDTO.class);
        if (response != null) {
            governanceManager.recordSuccess(operation);
            metrics.record(operation, System.currentTimeMillis() - start, true, false, null);
            recordTokenUsage(operation, request, response.getSummary());
            return response;
        }
        governanceManager.recordFailure(operation);
        metrics.record(operation, System.currentTimeMillis() - start, false, true, null);
        return fallbackSummary(request);
    }

    @Override
    public TicketCategoryDTO classify(TicketAiRequest request) {
        String operation = "ticket.classify";
        long start = System.currentTimeMillis();
        AiCallPermit permit = governanceManager.tryAcquire(operation);
        if (!permit.isAllowed()) {
            metrics.recordRejected(operation, permit.getReason());
            return fallbackCategory(request);
        }
        TicketCategoryDTO response = callAgent("/agent/ticket/classify", request, TicketCategoryDTO.class);
        if (response != null) {
            governanceManager.recordSuccess(operation);
            metrics.record(operation, System.currentTimeMillis() - start, true, false, null);
            recordTokenUsage(operation, request, response.getCategory());
            return response;
        }
        governanceManager.recordFailure(operation);
        metrics.record(operation, System.currentTimeMillis() - start, false, true, null);
        return fallbackCategory(request);
    }

    private void recordTokenUsage(String operation, TicketAiRequest request, String outputText) {
        AiTokenCost cost = governanceManager.estimateCost(JSONUtil.toJsonStr(request), outputText);
        metrics.recordTokenUsage(operation, cost.getInputTokens(), cost.getOutputTokens(), cost.getEstimatedCostUsd());
    }

    private <T> T callAgent(String path, TicketAiRequest request, Class<T> responseType) {
        try {
            return agentRestTemplate.postForObject(endpointRegistry.baseUrls().get(0) + path, request, responseType);
        } catch (RuntimeException e) {
            log.warn("AI provider fallback: path={} reason={}", path, e.getClass().getSimpleName());
            return null;
        }
    }

    private TicketSummaryDTO fallbackSummary(TicketAiRequest request) {
        TicketSummaryDTO dto = new TicketSummaryDTO();
        String text = buildTicketText(request);
        dto.setSummary(text.length() <= 160 ? text : text.substring(0, 160));
        return dto;
    }

    private TicketCategoryDTO fallbackCategory(TicketAiRequest request) {
        TicketCategoryDTO dto = new TicketCategoryDTO();
        String text = buildTicketText(request).toLowerCase();
        if (containsAny(text, "repair", "broken", "leak", "dorm")) {
            dto.setCategory("repair");
        } else if (containsAny(text, "print", "printer")) {
            dto.setCategory("printing");
        } else if (containsAny(text, "express", "parcel", "package")) {
            dto.setCategory("express");
        } else if (containsAny(text, "career", "resume", "job")) {
            dto.setCategory("consultation");
        } else {
            dto.setCategory("general");
        }
        dto.setConfidence(0.6D);
        return dto;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildTicketText(TicketAiRequest request) {
        if (request == null) {
            return "";
        }
        return (safe(request.getTitle()) + " " + safe(request.getContent())).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
