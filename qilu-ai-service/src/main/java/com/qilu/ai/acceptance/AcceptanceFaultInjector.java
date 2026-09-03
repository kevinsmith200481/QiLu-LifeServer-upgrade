package com.qilu.ai.acceptance;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class AcceptanceFaultInjector {

    private final AcceptanceFaultProperties properties;
    private final Environment environment;

    public AcceptanceFaultInjector(AcceptanceFaultProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public void beforeProviderBusiness() {
        if (properties.isEnabled()
                && properties.isProviderBusinessException()
                && environment.acceptsProfiles(Profiles.of("acceptance"))) {
            throw new IllegalStateException("ACCEPTANCE_PROVIDER_BUSINESS_EXCEPTION");
        }
    }
}
