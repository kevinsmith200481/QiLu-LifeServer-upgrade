package com.qilu.enums;

/**
 * RabbitMQ publication lifecycle for an inbox Outbox row.
 *
 * <p>This state never represents whether user copies were expanded; that is
 * tracked separately by {@link InboxDeliveryStatus}.</p>
 */
public enum InboxPublishStatus {

    PENDING,
    PUBLISHING,
    PUBLISHED,
    RETRY_WAIT,
    DEAD
}
