package com.qilu.service;

import com.qilu.acceptance.AcceptanceFaultInjector;
import com.qilu.config.InboxMqConfig;
import com.qilu.config.InboxOutboxProperties;
import com.qilu.dto.InboxDeliveryEvent;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxPublishStatus;
import com.qilu.mapper.InboxDeliveryTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class InboxOutboxRelayService {

    private static final int ERROR_MAX_LENGTH = 512;

    private final InboxDeliveryTaskMapper taskMapper;
    private final RabbitTemplate rabbitTemplate;
    private final InboxOutboxProperties properties;
    private final AcceptanceFaultInjector faultInjector;
    private final InboxOutboxMetrics metrics;
    private final Executor executor;
    private final String leaseOwner = "inbox-relay-" + UUID.randomUUID();

    public InboxOutboxRelayService(InboxDeliveryTaskMapper taskMapper,
                                   RabbitTemplate rabbitTemplate,
                                   InboxOutboxProperties properties,
                                   AcceptanceFaultInjector faultInjector,
                                   InboxOutboxMetrics metrics,
                                   @Qualifier("inboxOutboxExecutor") Executor executor) {
        this.taskMapper = taskMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.faultInjector = faultInjector;
        this.metrics = metrics;
        this.executor = executor;
    }

    /**
     * Best-effort latency optimization after commit. Scheduled scanning remains
     * the correctness path when this executor is full or the process crashes.
     */
    public void triggerImmediate(Long taskId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            executor.execute(() -> relayTask(taskId));
        } catch (RuntimeException e) {
            log.warn("inbox immediate relay rejected, taskId={}, traceId={}", taskId, traceId(), e);
        }
    }

    public int relayReadyTasks() {
        if (!properties.isEnabled()) {
            return 0;
        }
        List<InboxDeliveryTask> tasks = taskMapper.selectReadyForPublish(properties.getBatchSize());
        int claimed = 0;
        for (InboxDeliveryTask task : tasks) {
            if (relayTask(task.getId())) {
                claimed++;
            }
        }
        return claimed;
    }

    public boolean relayTask(Long taskId) {
        InboxDeliveryTask candidate = taskMapper.selectById(taskId);
        if (candidate == null) {
            return false;
        }
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(properties.getLeaseSeconds());
        int claimed = taskMapper.claimForPublish(
                candidate.getId(), candidate.getVersion(), leaseOwner, leaseUntil
        );
        if (claimed != 1) {
            return false;
        }

        InboxDeliveryTask task = taskMapper.selectById(taskId);
        try {
            publishAndAwaitConfirmation(task);
            faultInjector.haltAfterInboxPublishConfirm();
            int updated = taskMapper.markPublished(task.getId(), leaseOwner);
            if (updated != 1) {
                InboxDeliveryTask latest = taskMapper.selectById(task.getId());
                if (latest == null || !InboxPublishStatus.RETRY_WAIT.name().equals(latest.getPublishStatus())) {
                    throw new IllegalStateException("Outbox lease lost before publish ACK persistence");
                }
                // The consumer can fail and schedule the next confirmed publish
                // before the publisher thread receives its ACK. RETRY_WAIT is
                // therefore a valid newer state and must not be overwritten.
            }
            metrics.publishSuccess();
            log.info("inbox outbox published, taskNo={}, messageId={}, publishAttempt={}, "
                            + "deliveryAttempt={}, traceId={}",
                    task.getTaskNo(), task.getMessageId(), task.getPublishAttempts(),
                    task.getDeliveryAttempts(), traceId());
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordPublishFailure(task, e);
            return true;
        }
    }

    private void publishAndAwaitConfirmation(InboxDeliveryTask task) throws Exception {
        InboxDeliveryEvent event = new InboxDeliveryEvent();
        event.setTaskId(task.getId());
        event.setTaskNo(task.getTaskNo());
        event.setMonthKey(task.getMonthKey());
        event.setMessageId(task.getMessageId());

        faultInjector.beforeMqPublish();
        CorrelationData correlation = new CorrelationData(
                task.getTaskNo() + ":" + task.getPublishAttempts()
        );
        rabbitTemplate.convertAndSend(
                InboxMqConfig.INBOX_EXCHANGE, properties.getRoutingKey(), event, correlation
        );
        CorrelationData.Confirm confirm = correlation.getFuture().get(
                properties.getConfirmTimeoutMillis(), TimeUnit.MILLISECONDS
        );
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new IllegalStateException("RabbitMQ returned unroutable message: " + returned.getReplyText());
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("RabbitMQ publisher NACK: " + confirm.getReason());
        }
    }

    private void recordPublishFailure(InboxDeliveryTask task, Exception failure) {
        boolean retrying = task.getPublishAttempts() < properties.getMaxPublishAttempts();
        String status = retrying ? InboxPublishStatus.RETRY_WAIT.name() : InboxPublishStatus.DEAD.name();
        LocalDateTime retryTime = retrying ? InboxOutboxBackoff.nextTime(task.getPublishAttempts()) : null;
        String error = safeError(failure);
        int updated = taskMapper.markPublishFailure(
                task.getId(), leaseOwner, status, retryTime, error
        );
        metrics.publishFailure(retrying);
        log.warn("inbox outbox publish failed, taskNo={}, messageId={}, publishAttempt={}, "
                        + "deliveryAttempt={}, nextStatus={}, traceId={}, stateUpdated={}",
                task.getTaskNo(), task.getMessageId(), task.getPublishAttempts(),
                task.getDeliveryAttempts(), status, traceId(), updated == 1, failure);
    }

    private String safeError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() <= ERROR_MAX_LENGTH ? message : message.substring(0, ERROR_MAX_LENGTH);
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "-" : traceId;
    }
}
