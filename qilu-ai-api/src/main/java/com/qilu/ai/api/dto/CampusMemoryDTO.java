package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 主服务构造的会话级 Memory v2 契约。
 *
 * 该对象只携带有界轮次、脱敏摘要和受控实体 ID，不承载实时业务状态或权限结论。
 */
public class CampusMemoryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String mode;
    private String schemaVersion;
    private String conversationId;
    private List<CampusMemoryTurnDTO> recentTurns;
    private String rollingSummary;
    private CampusMemoryEntitiesDTO entities;
    private Long lastProcessedMessageId;
    private Long summaryVersion;
    private Boolean truncated;
    private Integer estimatedTokens;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public List<CampusMemoryTurnDTO> getRecentTurns() {
        return recentTurns;
    }

    public void setRecentTurns(List<CampusMemoryTurnDTO> recentTurns) {
        this.recentTurns = recentTurns;
    }

    public String getRollingSummary() {
        return rollingSummary;
    }

    public void setRollingSummary(String rollingSummary) {
        this.rollingSummary = rollingSummary;
    }

    public CampusMemoryEntitiesDTO getEntities() {
        return entities;
    }

    public void setEntities(CampusMemoryEntitiesDTO entities) {
        this.entities = entities;
    }

    public Long getLastProcessedMessageId() {
        return lastProcessedMessageId;
    }

    public void setLastProcessedMessageId(Long lastProcessedMessageId) {
        this.lastProcessedMessageId = lastProcessedMessageId;
    }

    public Long getSummaryVersion() {
        return summaryVersion;
    }

    public void setSummaryVersion(Long summaryVersion) {
        this.summaryVersion = summaryVersion;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public void setTruncated(Boolean truncated) {
        this.truncated = truncated;
    }

    public Integer getEstimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(Integer estimatedTokens) {
        this.estimatedTokens = estimatedTokens;
    }
}
