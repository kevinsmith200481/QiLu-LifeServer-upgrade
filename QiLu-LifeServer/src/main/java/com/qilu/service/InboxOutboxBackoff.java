package com.qilu.service;

import java.time.LocalDateTime;

/** Fixed retry policy required by the inbox reliability contract. */
public final class InboxOutboxBackoff {

    private static final long[] DELAYS_SECONDS = {1L, 5L, 30L, 120L, 600L};

    private InboxOutboxBackoff() {
    }

    public static LocalDateTime nextTime(int oneBasedAttempt) {
        int index = Math.max(0, Math.min(oneBasedAttempt - 1, DELAYS_SECONDS.length - 1));
        return LocalDateTime.now().plusSeconds(DELAYS_SECONDS[index]);
    }
}
