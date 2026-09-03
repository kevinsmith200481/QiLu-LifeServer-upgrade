package com.qilu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** AI 会话 Memory 的模式、Schema 与有界上下文配置。 */
@Component
@ConfigurationProperties(prefix = "qilu.ai.memory")
public class AiMemoryProperties {

    private static final Set<String> SUPPORTED_MODES = new HashSet<>(
            Arrays.asList("legacy", "shadow", "v2"));

    private String mode = "legacy";
    private String schemaVersion = "2";
    private int recentTurns = 8;
    private int rebuildTurns = 100;
    private int maxInputTokens = 3000;
    private int maxTurnChars = 1200;
    private int summaryMaxChars = 1000;
    private int summaryTriggerTurns = 4;
    private int summaryTriggerTokens = 1800;
    private boolean summarizerEnabled = false;
    private int summarizerTimeoutSeconds = 8;
    private int summarizerMaxRetries = 1;
    private int summarizerThreads = 1;
    private int summarizerQueueCapacity = 32;

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

    public int getRecentTurns() {
        return recentTurns;
    }

    public void setRecentTurns(int recentTurns) {
        this.recentTurns = recentTurns;
    }

    public int getRebuildTurns() {
        return rebuildTurns;
    }

    public void setRebuildTurns(int rebuildTurns) {
        this.rebuildTurns = rebuildTurns;
    }

    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    public void setMaxInputTokens(int maxInputTokens) {
        this.maxInputTokens = maxInputTokens;
    }

    public int getMaxTurnChars() {
        return maxTurnChars;
    }

    public void setMaxTurnChars(int maxTurnChars) {
        this.maxTurnChars = maxTurnChars;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }

    public int getSummaryTriggerTurns() {
        return summaryTriggerTurns;
    }

    public void setSummaryTriggerTurns(int summaryTriggerTurns) {
        this.summaryTriggerTurns = summaryTriggerTurns;
    }

    public int getSummaryTriggerTokens() {
        return summaryTriggerTokens;
    }

    public void setSummaryTriggerTokens(int summaryTriggerTokens) {
        this.summaryTriggerTokens = summaryTriggerTokens;
    }

    public boolean isSummarizerEnabled() {
        return summarizerEnabled;
    }

    public void setSummarizerEnabled(boolean summarizerEnabled) {
        this.summarizerEnabled = summarizerEnabled;
    }

    public int getSummarizerTimeoutSeconds() {
        return summarizerTimeoutSeconds;
    }

    public void setSummarizerTimeoutSeconds(int summarizerTimeoutSeconds) {
        this.summarizerTimeoutSeconds = summarizerTimeoutSeconds;
    }

    public int getSummarizerMaxRetries() {
        return summarizerMaxRetries;
    }

    public void setSummarizerMaxRetries(int summarizerMaxRetries) {
        this.summarizerMaxRetries = summarizerMaxRetries;
    }

    public int getSummarizerThreads() {
        return summarizerThreads;
    }

    public void setSummarizerThreads(int summarizerThreads) {
        this.summarizerThreads = summarizerThreads;
    }

    public int getSummarizerQueueCapacity() {
        return summarizerQueueCapacity;
    }

    public void setSummarizerQueueCapacity(int summarizerQueueCapacity) {
        this.summarizerQueueCapacity = summarizerQueueCapacity;
    }

    /** 启动或首次构建时失败即中止，防止错误预算静默进入生产链路。 */
    public void validate() {
        if (!SUPPORTED_MODES.contains(mode)) {
            throw new IllegalStateException("AI Memory mode must be legacy, shadow or v2");
        }
        if (!"2".equals(schemaVersion)) {
            throw new IllegalStateException("AI Memory schema version must be 2");
        }
        if (recentTurns <= 0 || recentTurns > 100 || rebuildTurns < recentTurns || rebuildTurns > 100) {
            throw new IllegalStateException("AI Memory recent/rebuild turn configuration is invalid");
        }
        if (maxInputTokens <= 0 || maxTurnChars <= 0 || summaryMaxChars <= 0
                || summaryMaxChars > maxInputTokens) {
            throw new IllegalStateException("AI Memory token or character budget is invalid");
        }
        if (summaryTriggerTurns <= 0 || summaryTriggerTurns > rebuildTurns
                || summaryTriggerTokens <= 0
                || summarizerTimeoutSeconds <= 0 || summarizerTimeoutSeconds > 60
                || summarizerMaxRetries < 0 || summarizerMaxRetries > 3
                || summarizerThreads <= 0 || summarizerThreads > 4
                || summarizerQueueCapacity <= 0 || summarizerQueueCapacity > 1000) {
            throw new IllegalStateException("AI Memory summarizer configuration is invalid");
        }
    }
}
