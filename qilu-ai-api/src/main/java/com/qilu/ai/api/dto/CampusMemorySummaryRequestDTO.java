package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 可选模型滚动摘要请求。
 *
 * 只允许旧脱敏摘要和有界轮次视图进入摘要器，不携带用户身份、实体候选、
 * 工具参数、业务状态或权限信息。
 */
public class CampusMemorySummaryRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String schemaVersion;
    private String conversationId;
    private Long baseVersion;
    private Long lastProcessedMessageId;
    private String previousSummary;
    private List<CampusMemoryTurnDTO> turns;
    private Integer maxSummaryChars;
    private Integer timeoutSeconds;
    private Integer maxRetries;

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

    public Long getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Long baseVersion) {
        this.baseVersion = baseVersion;
    }

    public Long getLastProcessedMessageId() {
        return lastProcessedMessageId;
    }

    public void setLastProcessedMessageId(Long lastProcessedMessageId) {
        this.lastProcessedMessageId = lastProcessedMessageId;
    }

    public String getPreviousSummary() {
        return previousSummary;
    }

    public void setPreviousSummary(String previousSummary) {
        this.previousSummary = previousSummary;
    }

    public List<CampusMemoryTurnDTO> getTurns() {
        return turns;
    }

    public void setTurns(List<CampusMemoryTurnDTO> turns) {
        this.turns = turns;
    }

    public Integer getMaxSummaryChars() {
        return maxSummaryChars;
    }

    public void setMaxSummaryChars(Integer maxSummaryChars) {
        this.maxSummaryChars = maxSummaryChars;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }
}
