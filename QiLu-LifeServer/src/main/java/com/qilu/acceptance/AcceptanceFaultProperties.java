package com.qilu.acceptance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fault switches used by automated acceptance tests.
 *
 * <p>All switches default to disabled. {@link AcceptanceFaultInjector} also
 * verifies the active Spring profile before applying any fault.</p>
 */
@Data
@ConfigurationProperties(prefix = "qilu.acceptance.fault")
public class AcceptanceFaultProperties {

    private boolean enabled;

    private boolean dbAfterOperation;

    private long mqPublishDelayMillis;

    private boolean rpcConnectionInterrupted;

    /** Number of upcoming appointment cancel Redis updates that must fail. */
    private int appointmentCancelRedisFailures;

    /** Number of consumer events that terminate the acceptance JVM before ACK. */
    private int appointmentConsumerHaltAfterPersist;

    /** Throw after the inbox message row is inserted but before its Outbox row. */
    private boolean inboxAfterMessageInsert;

    /** Number of upcoming inbox delivery attempts that must fail. */
    private int inboxDeliveryFailures;

    /** Number of relay confirmations after which the acceptance JVM must halt. */
    private int inboxPublishHaltAfterConfirm;
}
