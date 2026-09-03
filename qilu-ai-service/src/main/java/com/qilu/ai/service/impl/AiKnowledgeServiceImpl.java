package com.qilu.ai.service.impl;

import com.qilu.ai.api.dto.KnowledgeReloadRequest;
import com.qilu.ai.api.dto.KnowledgeReloadInstanceResult;
import com.qilu.ai.api.dto.KnowledgeReloadResponse;
import com.qilu.ai.api.service.AiKnowledgeService;
import com.qilu.ai.agent.AiAgentEndpointRegistry;
import gamer.springboot.starter.annotation.RpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@RpcService(interfaceClass = AiKnowledgeService.class)
public class AiKnowledgeServiceImpl implements AiKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(AiKnowledgeServiceImpl.class);
    private final AiAgentEndpointRegistry endpointRegistry;
    private final RestTemplate agentRestTemplate;

    public AiKnowledgeServiceImpl(AiAgentEndpointRegistry endpointRegistry,
                                  @Qualifier("aiAgentRestTemplate") RestTemplate agentRestTemplate) {
        this.endpointRegistry = endpointRegistry;
        this.agentRestTemplate = agentRestTemplate;
    }

    @Override
    public KnowledgeReloadResponse reloadKnowledge(KnowledgeReloadRequest request) {
        int documentCount = request == null || request.getDocuments() == null ? 0 : request.getDocuments().size();
        String requestedKnowledgeVersion = request == null ? null : request.getKnowledgeVersion();
        List<String> baseUrls = endpointRegistry.baseUrls();
        int successCount = 0;
        StringBuilder messages = new StringBuilder();
        List<KnowledgeReloadInstanceResult> instanceResults = new ArrayList<KnowledgeReloadInstanceResult>();
        List<KnowledgeReloadResponse> responses = new ArrayList<KnowledgeReloadResponse>();
        for (int index = 0; index < baseUrls.size(); index++) {
            String baseUrl = baseUrls.get(index);
            String fallbackInstanceId = "agent-instance-" + (index + 1);
            try {
                log.info("Reload AI agent knowledge, baseUrl={}, documentCount={}, knowledgeVersion={}",
                        baseUrl, documentCount, requestedKnowledgeVersion);
                KnowledgeReloadResponse response = agentRestTemplate.postForObject(
                        baseUrl + "/agent/knowledge/reload",
                        request,
                        KnowledgeReloadResponse.class);
                if (response != null && isInstanceAtTarget(response, requestedKnowledgeVersion)) {
                    successCount++;
                    responses.add(response);
                    instanceResults.add(toInstanceResult(response, fallbackInstanceId));
                    appendMessage(messages, instanceLabel(response, fallbackInstanceId) + ": success");
                } else if (response != null) {
                    responses.add(response);
                    KnowledgeReloadInstanceResult instanceResult = toInstanceResult(response, fallbackInstanceId);
                    if (instanceResult.getErrorCode() == null) {
                        instanceResult.setErrorCode("RAG_INSTANCE_NOT_ACTIVATED");
                    }
                    instanceResults.add(instanceResult);
                    appendMessage(messages, instanceLabel(response, fallbackInstanceId) + ": "
                            + defaultErrorCode(response.getErrorCode(), "RAG_INSTANCE_NOT_ACTIVATED"));
                } else {
                    instanceResults.add(failedInstance(fallbackInstanceId, "RAG_AGENT_INVALID_RESPONSE"));
                    appendMessage(messages, fallbackInstanceId + ": RAG_AGENT_INVALID_RESPONSE");
                }
            } catch (RuntimeException e) {
                log.warn("AI agent knowledge reload failed, baseUrl={}, documentCount={}", baseUrl, documentCount, e);
                instanceResults.add(failedInstance(fallbackInstanceId, "RAG_AGENT_UNAVAILABLE"));
                appendMessage(messages, fallbackInstanceId + ": RAG_AGENT_UNAVAILABLE");
            }
        }

        boolean versionDiverged = hasVersionDivergence(instanceResults);
        boolean allInstancesReady = successCount == baseUrls.size() && !versionDiverged;
        KnowledgeReloadResponse aggregate = new KnowledgeReloadResponse();
        aggregate.setSuccess(allInstancesReady);
        aggregate.setActivated(allInstancesReady);
        aggregate.setDegraded(anyDegraded(responses));
        aggregate.setDocumentCount(documentCount);
        aggregate.setSourceDocumentCount(documentCount);
        aggregate.setChunkCount(consensusInteger(responses, true));
        aggregate.setKnowledgeVersion(requestedKnowledgeVersion == null
                ? consensusValue(instanceResults, true) : requestedKnowledgeVersion);
        aggregate.setIndexVersion(consensusValue(instanceResults, false));
        aggregate.setActiveKnowledgeVersion(consensusValue(instanceResults, true));
        aggregate.setActiveIndexVersion(consensusValue(instanceResults, false));
        aggregate.setBackendStates(aggregateBackendStates(responses));
        aggregate.setErrorCode(resolveAggregateErrorCode(successCount, baseUrls.size(), versionDiverged));
        aggregate.setInstanceCount(baseUrls.size());
        aggregate.setSyncedInstanceCount(successCount);
        aggregate.setInstanceResults(instanceResults);
        aggregate.setMessage(messages.toString());
        return aggregate;
    }

    private boolean isInstanceAtTarget(KnowledgeReloadResponse response, String requestedKnowledgeVersion) {
        if (!Boolean.TRUE.equals(response.getSuccess())) {
            return false;
        }
        String activeKnowledgeVersion = firstNonBlank(
                response.getActiveKnowledgeVersion(), response.getKnowledgeVersion());
        if (requestedKnowledgeVersion != null && !requestedKnowledgeVersion.equals(activeKnowledgeVersion)) {
            return false;
        }
        // 旧 Agent 没有 activated/activeIndexVersion 字段；只在这些字段全部缺失时沿用旧 success 语义。
        boolean legacyResponse = response.getActivated() == null
                && response.getActiveKnowledgeVersion() == null
                && response.getActiveIndexVersion() == null;
        if (legacyResponse) {
            return true;
        }
        if (Boolean.TRUE.equals(response.getActivated())) {
            return true;
        }
        return Objects.equals(response.getIndexVersion(), response.getActiveIndexVersion())
                && response.getActiveIndexVersion() != null;
    }

    private KnowledgeReloadInstanceResult toInstanceResult(
            KnowledgeReloadResponse response,
            String fallbackInstanceId) {
        KnowledgeReloadInstanceResult result = new KnowledgeReloadInstanceResult();
        result.setInstanceId(instanceLabel(response, fallbackInstanceId));
        result.setSuccess(response.getSuccess());
        result.setActivated(response.getActivated());
        result.setDegraded(response.getDegraded());
        result.setSourceDocumentCount(response.getSourceDocumentCount() == null
                ? response.getDocumentCount() : response.getSourceDocumentCount());
        result.setChunkCount(response.getChunkCount());
        result.setKnowledgeVersion(response.getKnowledgeVersion());
        result.setIndexVersion(response.getIndexVersion());
        result.setActiveKnowledgeVersion(firstNonBlank(
                response.getActiveKnowledgeVersion(), response.getKnowledgeVersion()));
        result.setActiveIndexVersion(firstNonBlank(
                response.getActiveIndexVersion(), response.getIndexVersion()));
        result.setBackendStates(response.getBackendStates());
        result.setCandidateCollection(response.getCandidateCollection());
        result.setErrorCode(response.getErrorCode());
        result.setMessage(response.getMessage());
        return result;
    }

    private KnowledgeReloadInstanceResult failedInstance(String instanceId, String errorCode) {
        KnowledgeReloadInstanceResult result = new KnowledgeReloadInstanceResult();
        result.setInstanceId(instanceId);
        result.setSuccess(false);
        result.setActivated(false);
        result.setDegraded(false);
        result.setErrorCode(errorCode);
        result.setMessage("AI agent knowledge reload failed");
        return result;
    }

    private boolean hasVersionDivergence(List<KnowledgeReloadInstanceResult> results) {
        Set<String> knowledgeVersions = new TreeSet<String>();
        Set<String> indexVersions = new TreeSet<String>();
        boolean missingKnowledgeVersion = false;
        boolean missingIndexVersion = false;
        for (KnowledgeReloadInstanceResult result : results) {
            if (result.getActiveKnowledgeVersion() != null) {
                knowledgeVersions.add(result.getActiveKnowledgeVersion());
            } else {
                missingKnowledgeVersion = true;
            }
            if (result.getActiveIndexVersion() != null) {
                indexVersions.add(result.getActiveIndexVersion());
            } else {
                missingIndexVersion = true;
            }
        }
        return knowledgeVersions.size() > 1
                || indexVersions.size() > 1
                || (missingKnowledgeVersion && !knowledgeVersions.isEmpty())
                || (missingIndexVersion && !indexVersions.isEmpty());
    }

    private String consensusValue(List<KnowledgeReloadInstanceResult> results, boolean knowledge) {
        String consensus = null;
        for (KnowledgeReloadInstanceResult result : results) {
            String value = knowledge ? result.getActiveKnowledgeVersion() : result.getActiveIndexVersion();
            if (value == null) {
                continue;
            }
            if (consensus != null && !consensus.equals(value)) {
                return null;
            }
            consensus = value;
        }
        return consensus;
    }

    private Integer consensusInteger(List<KnowledgeReloadResponse> responses, boolean chunkCount) {
        Integer consensus = null;
        for (KnowledgeReloadResponse response : responses) {
            Integer value = chunkCount ? response.getChunkCount() : response.getSourceDocumentCount();
            if (value == null) {
                continue;
            }
            if (consensus != null && !consensus.equals(value)) {
                return null;
            }
            consensus = value;
        }
        return consensus;
    }

    private boolean anyDegraded(List<KnowledgeReloadResponse> responses) {
        for (KnowledgeReloadResponse response : responses) {
            if (Boolean.TRUE.equals(response.getDegraded())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> aggregateBackendStates(List<KnowledgeReloadResponse> responses) {
        Set<String> backends = new TreeSet<String>();
        for (KnowledgeReloadResponse response : responses) {
            if (response.getBackendStates() != null) {
                backends.addAll(response.getBackendStates().keySet());
            }
        }
        Map<String, String> aggregate = new LinkedHashMap<String, String>();
        for (String backend : backends) {
            String state = null;
            boolean mixed = false;
            boolean initialized = false;
            for (KnowledgeReloadResponse response : responses) {
                String current = response.getBackendStates() == null
                        ? null : response.getBackendStates().get(backend);
                if (!initialized) {
                    state = current;
                    initialized = true;
                } else if (!Objects.equals(state, current)) {
                    mixed = true;
                    break;
                }
            }
            aggregate.put(backend, mixed ? "MIXED" : state);
        }
        return aggregate;
    }

    private String resolveAggregateErrorCode(int successCount, int instanceCount, boolean diverged) {
        if (diverged) {
            return "RAG_INSTANCE_VERSION_DIVERGENCE";
        }
        if (successCount == instanceCount) {
            return null;
        }
        return successCount == 0 ? "RAG_ALL_INSTANCES_FAILED" : "RAG_PARTIAL_INSTANCE_FAILURE";
    }

    private String instanceLabel(KnowledgeReloadResponse response, String fallback) {
        return firstNonBlank(response.getInstanceId(), fallback);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : second;
    }

    private String defaultErrorCode(String errorCode, String fallback) {
        return errorCode == null || errorCode.trim().isEmpty() ? fallback : errorCode;
    }

    private void appendMessage(StringBuilder messages, String message) {
        if (messages.length() > 0) {
            messages.append("; ");
        }
        messages.append(message);
    }

}
