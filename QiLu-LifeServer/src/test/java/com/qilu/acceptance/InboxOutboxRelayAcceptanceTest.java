package com.qilu.acceptance;

import com.qilu.config.InboxOutboxProperties;
import com.qilu.dto.InboxDeliveryEvent;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.enums.InboxPublishStatus;
import com.qilu.mapper.InboxDeliveryTaskMapper;
import com.qilu.service.InboxOutboxMetrics;
import com.qilu.service.InboxOutboxRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxOutboxRelayAcceptanceTest {

    private InboxDeliveryTaskMapper taskMapper;
    private RabbitTemplate rabbitTemplate;
    private InboxOutboxRelayService relayService;

    @BeforeEach
    void setUp() {
        taskMapper = mock(InboxDeliveryTaskMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        InboxOutboxProperties properties = new InboxOutboxProperties();
        properties.setConfirmTimeoutMillis(1000L);
        AcceptanceFaultProperties faultProperties = new AcceptanceFaultProperties();
        AcceptanceFaultInjector faultInjector = new AcceptanceFaultInjector(
                faultProperties, new MockEnvironment()
        );
        InboxOutboxMetrics metrics = new InboxOutboxMetrics(taskMapper);
        Executor directExecutor = Runnable::run;
        relayService = new InboxOutboxRelayService(
                taskMapper, rabbitTemplate, properties, faultInjector, metrics, directExecutor
        );
    }

    @Test
    void correlatedAckMarksClaimedTaskPublished() {
        stubClaimedTask();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(InboxDeliveryEvent.class), any(CorrelationData.class)
        );
        when(taskMapper.markPublished(eq(1L), anyString())).thenReturn(1);

        assertTrue(relayService.relayTask(1L));

        verify(taskMapper).markPublished(eq(1L), anyString());
    }

    @Test
    void mandatoryReturnNeverMarksTaskPublishedAndSchedulesRetry() {
        stubClaimedTask();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0]), 312, "NO_ROUTE", "qilu.inbox.exchange", "wrong.key"
            ));
            correlation.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(InboxDeliveryEvent.class), any(CorrelationData.class)
        );

        assertTrue(relayService.relayTask(1L));

        verify(taskMapper).markPublishFailure(
                eq(1L), anyString(), eq(InboxPublishStatus.RETRY_WAIT.name()),
                any(LocalDateTime.class), anyString()
        );
    }

    private void stubClaimedTask() {
        InboxDeliveryTask candidate = task(0, 0);
        InboxDeliveryTask claimed = task(1, 1);
        when(taskMapper.selectById(1L)).thenReturn(candidate, claimed);
        when(taskMapper.claimForPublish(eq(1L), eq(0), anyString(), any(LocalDateTime.class))).thenReturn(1);
    }

    private InboxDeliveryTask task(int version, int attempts) {
        InboxDeliveryTask task = new InboxDeliveryTask();
        task.setId(1L);
        task.setTaskNo("outbox-test-1");
        task.setMonthKey("202607");
        task.setMessageId(10L);
        task.setPublishStatus(InboxPublishStatus.PUBLISHING.name());
        task.setPublishAttempts(attempts);
        task.setDeliveryStatus(InboxDeliveryStatus.WAITING.name());
        task.setDeliveryAttempts(0);
        task.setVersion(version);
        return task;
    }
}
