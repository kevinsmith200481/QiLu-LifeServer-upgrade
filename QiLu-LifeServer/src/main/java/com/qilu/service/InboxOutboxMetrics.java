package com.qilu.service;

import com.qilu.mapper.InboxDeliveryTaskMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free Prometheus counters for the inbox Outbox.
 *
 * <p>The project does not require Actuator to expose these stage-specific
 * metrics. {@code InboxOutboxMetricsController} renders this snapshot in the
 * Prometheus text format.</p>
 */
@Component
public class InboxOutboxMetrics {

    private final InboxDeliveryTaskMapper taskMapper;
    private final AtomicLong publishSuccess = new AtomicLong();
    private final AtomicLong publishFailure = new AtomicLong();
    private final AtomicLong publishRetry = new AtomicLong();
    private final AtomicLong publishDead = new AtomicLong();
    private final AtomicLong deliverySuccess = new AtomicLong();
    private final AtomicLong deliveryRetry = new AtomicLong();
    private final AtomicLong deliveryDead = new AtomicLong();

    public InboxOutboxMetrics(InboxDeliveryTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public void publishSuccess() {
        publishSuccess.incrementAndGet();
    }

    public void publishFailure(boolean retrying) {
        publishFailure.incrementAndGet();
        if (retrying) {
            publishRetry.incrementAndGet();
        } else {
            publishDead.incrementAndGet();
        }
    }

    public void deliverySuccess() {
        deliverySuccess.incrementAndGet();
    }

    public void deliveryFailure(boolean retrying) {
        if (retrying) {
            deliveryRetry.incrementAndGet();
        } else {
            deliveryDead.incrementAndGet();
        }
    }

    public String prometheusSnapshot() {
        StringBuilder output = new StringBuilder(1024);
        gauge(output, "qilu_inbox_outbox_backlog", safeBacklog());
        gauge(output, "qilu_inbox_outbox_oldest_pending_age_seconds", safeOldestAgeSeconds());
        counter(output, "qilu_inbox_outbox_publish_success_total", publishSuccess.get());
        counter(output, "qilu_inbox_outbox_publish_failure_total", publishFailure.get());
        counter(output, "qilu_inbox_outbox_publish_retry_total", publishRetry.get());
        counter(output, "qilu_inbox_outbox_publish_dead_total", publishDead.get());
        counter(output, "qilu_inbox_outbox_delivery_success_total", deliverySuccess.get());
        counter(output, "qilu_inbox_outbox_delivery_retry_total", deliveryRetry.get());
        counter(output, "qilu_inbox_outbox_delivery_dead_total", deliveryDead.get());
        return output.toString();
    }

    private void counter(StringBuilder output, String name, long value) {
        output.append("# TYPE ").append(name).append(" counter\n")
                .append(name).append(' ').append(value).append('\n');
    }

    private void gauge(StringBuilder output, String name, double value) {
        output.append("# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(value).append('\n');
    }

    private double safeBacklog() {
        try {
            return taskMapper.countBacklog();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private double safeOldestAgeSeconds() {
        try {
            LocalDateTime oldest = taskMapper.selectOldestPendingTime();
            return oldest == null ? 0D : Math.max(0L, Duration.between(oldest, LocalDateTime.now()).getSeconds());
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }
}
