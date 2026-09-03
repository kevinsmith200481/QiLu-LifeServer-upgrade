package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class CampusAssistantResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String answer;
    private String intent;
    private String traceId;
    private Double confidence;
    private Boolean needCreateTicket;
    private List<CampusServicePointDTO> recommendedServicePoints;
    private List<CampusAssistantSourceDTO> sources;
    private List<Map<String, Object>> businessCards;
    private List<Map<String, Object>> actionDrafts;
    private String orchestrator;
    private List<Map<String, Object>> langGraphNodes;
    private List<Map<String, Object>> executionRecords;
    private List<Map<String, Object>> fallbackRecords;
    private String fallbackReason;
    private String serviceStage;
    private String errorStage;
    private String errorCode;
    private Boolean retriable;
    private String fallbackMessage;
    private Integer rpcAttempts;
    private String plannerMode;
    private String retrievalMode;
    private String intentSource;
    private String routingReason;
    private Boolean lowConfidence;
    private Map<String, Object> checkpoint;
    private CampusMemoryDiagnosticsDTO memoryDiagnostics;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Boolean getNeedCreateTicket() {
        return needCreateTicket;
    }

    public void setNeedCreateTicket(Boolean needCreateTicket) {
        this.needCreateTicket = needCreateTicket;
    }

    public List<CampusServicePointDTO> getRecommendedServicePoints() {
        return recommendedServicePoints;
    }

    public void setRecommendedServicePoints(List<CampusServicePointDTO> recommendedServicePoints) {
        this.recommendedServicePoints = recommendedServicePoints;
    }

    public List<CampusAssistantSourceDTO> getSources() {
        return sources;
    }

    public void setSources(List<CampusAssistantSourceDTO> sources) {
        this.sources = sources;
    }

    public List<Map<String, Object>> getBusinessCards() {
        return businessCards;
    }

    public void setBusinessCards(List<Map<String, Object>> businessCards) {
        this.businessCards = businessCards;
    }

    public List<Map<String, Object>> getActionDrafts() {
        return actionDrafts;
    }

    public void setActionDrafts(List<Map<String, Object>> actionDrafts) {
        this.actionDrafts = actionDrafts;
    }

    public String getOrchestrator() {
        return orchestrator;
    }

    public void setOrchestrator(String orchestrator) {
        this.orchestrator = orchestrator;
    }

    public List<Map<String, Object>> getLangGraphNodes() {
        return langGraphNodes;
    }

    public void setLangGraphNodes(List<Map<String, Object>> langGraphNodes) {
        this.langGraphNodes = langGraphNodes;
    }

    public List<Map<String, Object>> getExecutionRecords() {
        return executionRecords;
    }

    public void setExecutionRecords(List<Map<String, Object>> executionRecords) {
        this.executionRecords = executionRecords;
    }

    public List<Map<String, Object>> getFallbackRecords() {
        return fallbackRecords;
    }

    public void setFallbackRecords(List<Map<String, Object>> fallbackRecords) {
        this.fallbackRecords = fallbackRecords;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public void setFallbackReason(String fallbackReason) {
        this.fallbackReason = fallbackReason;
    }

    public String getServiceStage() {
        return serviceStage;
    }

    public void setServiceStage(String serviceStage) {
        this.serviceStage = serviceStage;
    }

    public String getErrorStage() {
        return errorStage;
    }

    public void setErrorStage(String errorStage) {
        this.errorStage = errorStage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Boolean getRetriable() {
        return retriable;
    }

    public void setRetriable(Boolean retriable) {
        this.retriable = retriable;
    }

    public String getFallbackMessage() {
        return fallbackMessage;
    }

    public void setFallbackMessage(String fallbackMessage) {
        this.fallbackMessage = fallbackMessage;
    }

    public Integer getRpcAttempts() {
        return rpcAttempts;
    }

    public void setRpcAttempts(Integer rpcAttempts) {
        this.rpcAttempts = rpcAttempts;
    }

    public String getPlannerMode() {
        return plannerMode;
    }

    public void setPlannerMode(String plannerMode) {
        this.plannerMode = plannerMode;
    }

    public String getRetrievalMode() {
        return retrievalMode;
    }

    public void setRetrievalMode(String retrievalMode) {
        this.retrievalMode = retrievalMode;
    }

    public String getIntentSource() {
        return intentSource;
    }

    public void setIntentSource(String intentSource) {
        this.intentSource = intentSource;
    }

    public String getRoutingReason() {
        return routingReason;
    }

    public void setRoutingReason(String routingReason) {
        this.routingReason = routingReason;
    }

    public Boolean getLowConfidence() {
        return lowConfidence;
    }

    public void setLowConfidence(Boolean lowConfidence) {
        this.lowConfidence = lowConfidence;
    }

    public Map<String, Object> getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(Map<String, Object> checkpoint) {
        this.checkpoint = checkpoint;
    }

    public CampusMemoryDiagnosticsDTO getMemoryDiagnostics() {
        return memoryDiagnostics;
    }

    public void setMemoryDiagnostics(CampusMemoryDiagnosticsDTO memoryDiagnostics) {
        this.memoryDiagnostics = memoryDiagnostics;
    }
}
