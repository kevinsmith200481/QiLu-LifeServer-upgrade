package com.qilu.acceptance;

import com.qilu.config.InboxOutboxProperties;
import com.qilu.dto.UserDTO;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.service.IInboxOutboxAdminService;
import com.qilu.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfSystemProperty(named = "acceptance.inbox-outbox", matches = "true")
@TestPropertySource(properties = {
        "qilu.inbox.outbox.enabled=false",
        "qilu.acceptance.fault.enabled=true",
        "qilu.acceptance.fault.inbox-delivery-failures=5"
})
class InboxOutboxFailureInjectionAcceptanceTest extends InboxOutboxAcceptanceSupport {

    @Resource
    private IInboxOutboxAdminService adminService;

    @Resource
    private InboxOutboxProperties outboxProperties;

    @Test
    void repeatedDeliveryFailureCreatesOneDeadLetterAndManualRetrySucceeds() throws Exception {
        InboxDeliveryTask task = sendToStudent("outbox-dead-retry-" + System.nanoTime());
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                deliveryService.consumeDelivery(event(task));
                fail("acceptance fault must fail delivery attempt " + (attempt + 1));
            } catch (RuntimeException expected) {
                deliveryService.recordDeliveryFailure(event(task), expected);
            }
        }

        InboxDeliveryTask dead = taskMapper.selectById(task.getId());
        assertEquals(InboxDeliveryStatus.DEAD.name(), dead.getDeliveryStatus());
        assertEquals(1L, deadLetterMapper.selectCount(null));

        outboxProperties.setEnabled(true);
        UserHolder.saveUser(adminUser());
        try {
            assertTrue(Boolean.TRUE.equals(adminService.retryDeadTask(task.getId()).getSuccess()));
        } finally {
            UserHolder.removeUser();
        }

        InboxDeliveryTask delivered = waitForDeliveryStatus(
                task.getId(), InboxDeliveryStatus.SUCCESS, 30_000L
        );
        assertEquals(InboxDeliveryStatus.SUCCESS.name(), delivered.getDeliveryStatus());
        assertEquals(1L, userCopyCount(task));
        assertEquals(1L, deadLetterMapper.selectCount(null), "manual replay must not duplicate dead letter");
    }

    private UserDTO adminUser() {
        UserDTO user = new UserDTO();
        user.setId(ADMIN_ID);
        user.setNickName("acceptance-admin");
        user.setRole("admin");
        return user;
    }
}
