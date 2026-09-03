package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class KnowledgeReloadResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean success;
    private Boolean activated;
    private Boolean degraded;
    private Integer documentCount;
    private Integer sourceDocumentCount;
    private Integer chunkCount;
    private String message;
    private String knowledgeVersion;
    private String indexVersion;
    private String activeKnowledgeVersion;
    private String activeIndexVersion;
    private Map<String, String> backendStates;
    private String candidateCollection;
    private String errorCode;
    private String instanceId;
    private Integer instanceCount;
    private Integer syncedInstanceCount;
    private List<KnowledgeReloadInstanceResult> instanceResults;

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

    public Integer getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(Integer documentCount) {
        this.documentCount = documentCount;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Integer getInstanceCount() {
        return instanceCount;
    }

    public void setInstanceCount(Integer instanceCount) {
        this.instanceCount = instanceCount;
    }

    public Integer getSyncedInstanceCount() {
        return syncedInstanceCount;
    }

    public void setSyncedInstanceCount(Integer syncedInstanceCount) {
        this.syncedInstanceCount = syncedInstanceCount;
    }

    public List<KnowledgeReloadInstanceResult> getInstanceResults() {
        return instanceResults;
    }

    public void setInstanceResults(List<KnowledgeReloadInstanceResult> instanceResults) {
        this.instanceResults = instanceResults;
    }
}
