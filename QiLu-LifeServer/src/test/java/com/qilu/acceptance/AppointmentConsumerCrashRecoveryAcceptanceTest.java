package com.qilu.acceptance;

import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.enums.AppointmentOrderStatus;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs in a fresh JVM after the crash trigger. The normal consumer must claim
 * the stale pending record, recognize the already persisted order, and ACK it
 * without applying the database or Redis quota transition a second time.
 */
@SpringBootTest(properties = "qilu.appointment.consumer.enabled=true")
@EnabledIfSystemProperty(named = "acceptance.appointment-crash-recovery", matches = "true")
class AppointmentConsumerCrashRecoveryAcceptanceTest {

    private static final long SLOT_ID = AppointmentConsumerCrashTriggerAcceptanceTest.SLOT_ID;
    private static final long USER_ID = 9_801_001L;
    private static final String GROUP_NAME = "g1";

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void restartedConsumerAcknowledgesPersistedPendingMessageExactlyOnce() throws Exception {
        boolean recovered = false;
        for (int attempt = 0; attempt < 60; attempt++) {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    RedisConstants.APPOINTMENT_ORDER_STREAM_KEY,
                    GROUP_NAME,
                    Range.unbounded(),
                    10
            );
            if (pending == null || pending.isEmpty()) {
                recovered = true;
                break;
            }
            Thread.sleep(Duration.ofMillis(500).toMillis());
        }

        assertTrue(recovered, "pending appointment message must be recovered within 30 seconds");
        List<AppointmentOrder> activeOrders = appointmentOrderService.query()
                .eq("slot_id", SLOT_ID)
                .eq("user_id", USER_ID)
                .eq("status", AppointmentOrderStatus.RESERVED.getCode())
                .list();
        AppointmentSlot slot = appointmentSlotService.getById(SLOT_ID);

        assertEquals(1, activeOrders.size(), "restart must not create a duplicate active order");
        assertEquals(0, slot.getAvailableQuota(), "database quota must be deducted exactly once");
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + SLOT_ID));
        assertEquals(Boolean.TRUE, stringRedisTemplate.opsForSet().isMember(
                RedisConstants.APPOINTMENT_ORDER_KEY + SLOT_ID,
                String.valueOf(USER_ID)
        ));
    }
}
