package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 不含正文和业务 ID 的 Memory 诊断信息。
 *
 * 该结构可进入管理 Trace，但不得扩展为问题、摘要、实体 ID 或模型提示的载体。
 */
public class CampusMemoryDiagnosticsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String mode;
    private String schemaVersion;
    private Integer recentTurnCount;
    private Long summaryVersion;
    private List<String> entityTypes;
    private String resolutionSource;
    private Boolean degraded;
    private String degradedReason;

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

    public Integer getRecentTurnCount() {
        return recentTurnCount;
    }

    public void setRecentTurnCount(Integer recentTurnCount) {
        this.recentTurnCount = recentTurnCount;
    }

    public Long getSummaryVersion() {
        return summaryVersion;
    }

    public void setSummaryVersion(Long summaryVersion) {
        this.summaryVersion = summaryVersion;
    }

    public List<String> getEntityTypes() {
        return entityTypes;
    }

    public void setEntityTypes(List<String> entityTypes) {
        this.entityTypes = entityTypes;
    }

    public String getResolutionSource() {
        return resolutionSource;
    }

    public void setResolutionSource(String resolutionSource) {
        this.resolutionSource = resolutionSource;
    }

    public Boolean getDegraded() {
        return degraded;
    }

    public void setDegraded(Boolean degraded) {
        this.degraded = degraded;
    }

    public String getDegradedReason() {
        return degradedReason;
    }

    public void setDegradedReason(String degradedReason) {
        this.degradedReason = degradedReason;
    }
}
