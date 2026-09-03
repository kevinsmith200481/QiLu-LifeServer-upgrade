package com.qilu.acceptance;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AcceptanceFaultInjector {

    private final AcceptanceFaultProperties properties;
    private final Environment environment;
    private final AtomicInteger appointmentCancelRedisFailuresRemaining;
    private final AtomicInteger appointmentConsumerHaltsRemaining;
    private final AtomicInteger inboxDeliveryFailuresRemaining;
    private final AtomicInteger inboxPublishHaltsRemaining;

    public AcceptanceFaultInjector(AcceptanceFaultProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
        this.appointmentCancelRedisFailuresRemaining = new AtomicInteger(
                Math.max(properties.getAppointmentCancelRedisFailures(), 0)
        );
        this.appointmentConsumerHaltsRemaining = new AtomicInteger(
                Math.max(properties.getAppointmentConsumerHaltAfterPersist(), 0)
        );
        this.inboxDeliveryFailuresRemaining = new AtomicInteger(
                Math.max(properties.getInboxDeliveryFailures(), 0)
        );
        this.inboxPublishHaltsRemaining = new AtomicInteger(
                Math.max(properties.getInboxPublishHaltAfterConfirm(), 0)
        );
    }

    public void afterDatabaseOperation() {
        if (faultsEnabled() && properties.isDbAfterOperation()) {
            throw new AcceptanceInjectedFaultException("ACCEPTANCE_DB_AFTER_OPERATION");
        }
    }

    public void beforeMqPublish() {
        if (!faultsEnabled() || properties.getMqPublishDelayMillis() <= 0) {
            return;
        }
        try {
            Thread.sleep(properties.getMqPublishDelayMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcceptanceInjectedFaultException("ACCEPTANCE_MQ_PUBLISH_INTERRUPTED");
        }
    }

    public void afterInboxMessageInsert() {
        if (faultsEnabled() && properties.isInboxAfterMessageInsert()) {
            throw new AcceptanceInjectedFaultException("ACCEPTANCE_INBOX_AFTER_MESSAGE_INSERT");
        }
    }

    public void beforeInboxDelivery() {
        if (!faultsEnabled()) {
            return;
        }
        int remaining = inboxDeliveryFailuresRemaining.getAndUpdate(value -> Math.max(value - 1, 0));
        if (remaining > 0) {
            throw new AcceptanceInjectedFaultException("ACCEPTANCE_INBOX_DELIVERY_FAILURE");
        }
    }

    public void haltAfterInboxPublishConfirm() {
        if (!faultsEnabled()) {
            return;
        }
        int remaining = inboxPublishHaltsRemaining.getAndUpdate(value -> Math.max(value - 1, 0));
        if (remaining > 0) {
            // Process-level crash acceptance: the broker has ACKed, but the DB
            // row deliberately remains PUBLISHING until its lease expires.
            Runtime.getRuntime().halt(92);
        }
    }

    public void beforeRpcInvocation() {
        if (faultsEnabled() && properties.isRpcConnectionInterrupted()) {
            throw new AcceptanceInjectedFaultException("ACCEPTANCE_RPC_CONNECTION_INTERRUPTED");
        }
    }

    public void beforeAppointmentCancelRedisUpdate() {
        if (!faultsEnabled()) {
            return;
        }
        int remaining = appointmentCancelRedisFailuresRemaining.getAndUpdate(value -> Math.max(value - 1, 0));
        if (remaining > 0) {
            throw new AcceptanceInjectedFaultException("ACCEPTANCE_APPOINTMENT_CANCEL_REDIS_FAILURE");
        }
    }

    public void haltAfterAppointmentPersistBeforeAck() {
        if (!faultsEnabled()) {
            return;
        }
        int remaining = appointmentConsumerHaltsRemaining.getAndUpdate(value -> Math.max(value - 1, 0));
        if (remaining > 0) {
            // Intentional hard halt for process-level acceptance: no shutdown
            // hook or finally block may ACK the already persisted Stream event.
            Runtime.getRuntime().halt(91);
        }
    }

    private boolean faultsEnabled() {
        // The profile check is deliberate defense in depth against a copied
        // environment variable enabling destructive test behavior elsewhere.
        return properties.isEnabled() && environment.acceptsProfiles(Profiles.of("acceptance"));
    }
}
