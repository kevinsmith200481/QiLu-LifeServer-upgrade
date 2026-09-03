package com.qilu.ai.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcceptanceFaultInjectorTest {

    @Test
    void providerFaultRequiresAcceptanceProfile() {
        AcceptanceFaultProperties properties = new AcceptanceFaultProperties();
        properties.setEnabled(true);
        properties.setProviderBusinessException(true);

        MockEnvironment defaultEnvironment = new MockEnvironment();
        AcceptanceFaultInjector defaultInjector = new AcceptanceFaultInjector(properties, defaultEnvironment);
        assertDoesNotThrow(defaultInjector::beforeProviderBusiness);

        MockEnvironment acceptanceEnvironment = new MockEnvironment();
        acceptanceEnvironment.setActiveProfiles("acceptance");
        AcceptanceFaultInjector acceptanceInjector = new AcceptanceFaultInjector(properties, acceptanceEnvironment);
        assertThrows(IllegalStateException.class, acceptanceInjector::beforeProviderBusiness);
    }
}
