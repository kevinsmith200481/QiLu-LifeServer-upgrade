package com.qilu.acceptance;

import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxDeliveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "acceptance.inbox-outbox", matches = "true")
class InboxDeliveryIdempotencyAcceptanceTest extends InboxOutboxAcceptanceSupport {

    @Test
    void replayingSameEventTenTimesKeepsOneCopyAndOneUnreadIncrement() throws Exception {
        long unreadBefore = unreadTotal(STUDENT_ID);
        InboxDeliveryTask task = sendToStudent("outbox-replay-" + System.nanoTime());
        InboxDeliveryTask delivered = waitForDeliveryStatus(
                task.getId(), InboxDeliveryStatus.SUCCESS, 30_000L
        );
        assertEquals(InboxDeliveryStatus.SUCCESS.name(), delivered.getDeliveryStatus());
        assertEquals(1L, userCopyCount(task));
        assertEquals(unreadBefore + 1, unreadTotal(STUDENT_ID));

        for (int replay = 0; replay < 10; replay++) {
            deliveryService.consumeDelivery(event(task));
        }

        assertEquals(1L, userCopyCount(task));
        assertEquals(unreadBefore + 1, unreadTotal(STUDENT_ID));
        assertEquals(0L, deadLetterMapper.selectCount(null));
    }
}
