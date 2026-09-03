package com.qilu.enums;

/**
 * Deterministic result of consuming one appointment reservation event.
 */
public enum AppointmentPersistenceOutcome {
    CREATED,
    ALREADY_PERSISTED,
    DUPLICATE_ACTIVE_ORDER,
    NO_QUOTA,
    SLOT_DISABLED,
    SLOT_EXPIRED
}
