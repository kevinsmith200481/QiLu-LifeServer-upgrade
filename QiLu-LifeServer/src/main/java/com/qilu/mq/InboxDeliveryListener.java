package com.qilu.mq;

import com.qilu.config.InboxMqConfig;
import com.qilu.dto.InboxDeliveryEvent;
import com.qilu.service.IInboxDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InboxDeliveryListener {

    private final IInboxDeliveryService inboxDeliveryService;

    public InboxDeliveryListener(IInboxDeliveryService inboxDeliveryService) {
        this.inboxDeliveryService = inboxDeliveryService;
    }

    @RabbitListener(queues = InboxMqConfig.INBOX_DELIVERY_QUEUE)
    public void onDelivery(InboxDeliveryEvent event) {
        try {
            inboxDeliveryService.consumeDelivery(event);
        } catch (Exception e) {
            // The exception is converted into durable retry state before this
            // listener returns. Rabbit TTL retries are not a correctness path.
            log.warn("inbox delivery failed, taskNo={}, messageId={}",
                    event.getTaskNo(), event.getMessageId(), e);
            inboxDeliveryService.recordDeliveryFailure(event, e);
        }
    }

    @RabbitListener(queues = InboxMqConfig.INBOX_DEAD_QUEUE)
    public void onDeadLetter(InboxDeliveryEvent event) {
        inboxDeliveryService.recordDeliveryFailure(
                event, new IllegalStateException("RabbitMQ dead-letter delivery")
        );
    }
}
