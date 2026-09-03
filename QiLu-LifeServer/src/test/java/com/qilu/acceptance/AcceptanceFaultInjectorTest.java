package com.qilu.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcceptanceFaultInjectorTest {

    @Test
    void ignoresEnabledSwitchOutsideAcceptanceProfile() {
        AcceptanceFaultProperties properties = enabledProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        AcceptanceFaultInjector injector = new AcceptanceFaultInjector(properties, environment);

        assertDoesNotThrow(injector::afterDatabaseOperation);
        assertDoesNotThrow(injector::beforeRpcInvocation);
    }

    @Test
    void appliesEnabledSwitchInsideAcceptanceProfile() {
        AcceptanceFaultProperties properties = enabledProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("acceptance");
        AcceptanceFaultInjector injector = new AcceptanceFaultInjector(properties, environment);

        assertThrows(AcceptanceInjectedFaultException.class, injector::afterDatabaseOperation);
        assertThrows(AcceptanceInjectedFaultException.class, injector::beforeRpcInvocation);
    }

    private AcceptanceFaultProperties enabledProperties() {
        AcceptanceFaultProperties properties = new AcceptanceFaultProperties();
        properties.setEnabled(true);
        properties.setDbAfterOperation(true);
        properties.setRpcConnectionInterrupted(true);
        return properties;
    }
}
