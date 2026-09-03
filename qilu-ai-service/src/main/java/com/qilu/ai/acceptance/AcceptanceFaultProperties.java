package com.qilu.ai.acceptance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qilu.acceptance.fault")
public class AcceptanceFaultProperties {

    private boolean enabled;

    private boolean providerBusinessException;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isProviderBusinessException() {
        return providerBusinessException;
    }

    public void setProviderBusinessException(boolean providerBusinessException) {
        this.providerBusinessException = providerBusinessException;
    }
}
