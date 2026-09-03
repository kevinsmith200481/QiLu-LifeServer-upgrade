package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.Map;

/**
 * 单个 Agent 实例的知识重载结果。只包含版本、计数、枚举状态和脱敏摘要。
 */
public class KnowledgeReloadInstanceResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String instanceId;
    private Boolean success;
    private Boolean activated;
    private Boolean degraded;
    private Integer sourceDocumentCount;
    private Integer chunkCount;
    private String knowledgeVersion;
    private String indexVersion;
    private String activeKnowledgeVersion;
    private String activeIndexVersion;
    private Map<String, String> backendStates;
    private String candidateCollection;
    private String errorCode;
    private String message;

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Boolean getActivated() {
        return activated;
    }

    public void setActivated(Boolean activated) {
        this.activated = activated;
    }

    public Boolean getDegraded() {
        return degraded;
    }

    public void setDegraded(Boolean degraded) {
        this.degraded = degraded;
    }

    public Integer getSourceDocumentCount() {
        return sourceDocumentCount;
    }

    public void setSourceDocumentCount(Integer sourceDocumentCount) {
        this.sourceDocumentCount = sourceDocumentCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getKnowledgeVersion() {
        return knowledgeVersion;
    }

    public void setKnowledgeVersion(String knowledgeVersion) {
        this.knowledgeVersion = knowledgeVersion;
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(String indexVersion) {
        this.indexVersion = indexVersion;
    }

    public String getActiveKnowledgeVersion() {
        return activeKnowledgeVersion;
    }

    public void setActiveKnowledgeVersion(String activeKnowledgeVersion) {
        this.activeKnowledgeVersion = activeKnowledgeVersion;
    }

    public String getActiveIndexVersion() {
        return activeIndexVersion;
    }

    public void setActiveIndexVersion(String activeIndexVersion) {
        this.activeIndexVersion = activeIndexVersion;
    }

    public Map<String, String> getBackendStates() {
        return backendStates;
    }

    public void setBackendStates(Map<String, String> backendStates) {
        this.backendStates = backendStates;
    }

    public String getCandidateCollection() {
        return candidateCollection;
    }

    public void setCandidateCollection(String candidateCollection) {
        this.candidateCollection = candidateCollection;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
