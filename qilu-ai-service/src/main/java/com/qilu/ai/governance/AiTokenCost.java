package com.qilu.ai.governance;

public class AiTokenCost {

    private final long inputTokens;
    private final long outputTokens;
    private final double estimatedCostUsd;

    public AiTokenCost(long inputTokens, long outputTokens, double estimatedCostUsd) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimatedCostUsd = estimatedCostUsd;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public double getEstimatedCostUsd() {
        return estimatedCostUsd;
    }
}
