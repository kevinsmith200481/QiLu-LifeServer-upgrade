package com.qilu.ai.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.governance")
public class AiGovernanceProperties {

    private int rateLimitPerMinute = 120;
    private int circuitFailureThreshold = 5;
    private long circuitOpenMillis = 30000L;
    private double inputPricePer1kTokensUsd = 0.0D;
    private double outputPricePer1kTokensUsd = 0.0D;

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public long getCircuitOpenMillis() {
        return circuitOpenMillis;
    }

    public void setCircuitOpenMillis(long circuitOpenMillis) {
        this.circuitOpenMillis = circuitOpenMillis;
    }

    public double getInputPricePer1kTokensUsd() {
        return inputPricePer1kTokensUsd;
    }

    public void setInputPricePer1kTokensUsd(double inputPricePer1kTokensUsd) {
        this.inputPricePer1kTokensUsd = inputPricePer1kTokensUsd;
    }

    public double getOutputPricePer1kTokensUsd() {
        return outputPricePer1kTokensUsd;
    }

    public void setOutputPricePer1kTokensUsd(double outputPricePer1kTokensUsd) {
        this.outputPricePer1kTokensUsd = outputPricePer1kTokensUsd;
    }
}
