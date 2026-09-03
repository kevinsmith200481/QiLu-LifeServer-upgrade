package com.qilu.acceptance;

import com.qilu.acceptance.AcceptanceInjectedFaultException;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AppointmentConsistencyRepair;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.enums.AppointmentOrderStatus;
import com.qilu.service.IAppointmentConsistencyRepairService;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IOperationLogService;
import com.qilu.service.impl.AppointmentConsistencyRepairServiceImpl;
import com.qilu.service.impl.AppointmentOrderServiceImpl;
import com.qilu.utils.RedisConstants;
import com.qilu.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "qilu.acceptance.fault.enabled=true",
        "qilu.acceptance.fault.db-after-operation=true",
        "qilu.acceptance.fault.appointment-cancel-redis-failures=1",
        "qilu.appointment.consumer.enabled=false"
})
@EnabledIfSystemProperty(named = "acceptance.appointment-phase1", matches = "true")
class AppointmentConsistencyRepairAcceptanceTest {

    private static final long SERVICE_POINT_ID = 990001L;
    private static final long USER_ID = 9_700_001L;
    private static final AtomicLong ORDER_ID = new AtomicLong(System.currentTimeMillis() * 100_000L);

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @Resource
    private AppointmentOrderServiceImpl appointmentOrderProcessor;

    @Resource
    private IAppointmentConsistencyRepairService appointmentConsistencyRepairService;

    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private IAppointmentNotificationService appointmentNotificationService;

    private final List<Long> createdSlotIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
        for (Long orderId : createdOrderIds) {
            appointmentConsistencyRepairService.remove(
                    appointmentConsistencyRepairService.query().eq("order_id", orderId).getWrapper()
            );
            operationLogService.remove(
                    operationLogService.query().eq("business_type", "APPOINTMENT").eq("business_id", orderId).getWrapper()
            );
            appointmentOrderService.removeById(orderId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_CANCEL_RELEASE_KEY + orderId);
        }
        for (Long slotId : createdSlotIds) {
            appointmentOrderService.remove(appointmentOrderService.query().eq("slot_id", slotId).getWrapper());
            appointmentSlotService.removeById(slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + slotId);
        }
        createdOrderIds.clear();
        createdSlotIds.clear();
    }

    @Test
    void orderInsertFailureRollsBackDatabaseQuotaDeduction() {
        AppointmentSlot slot = createSlot("phase1-order-insert-rollback", 1);
        long orderId = ORDER_ID.incrementAndGet();
        seedAcceptedRedisReservation(slot.getId(), USER_ID);

        assertThrows(AcceptanceInjectedFaultException.class,
                () -> appointmentOrderProcessor.processAppointmentEvent(orderId, USER_ID, slot.getId()));

        assertNull(appointmentOrderService.getById(orderId));
        assertEquals(1, appointmentSlotService.getById(slot.getId()).getAvailableQuota());
    }

    @Test
    void redisFailureAfterCancelIsRecoveredIdempotentlyByOrderId() {
        AppointmentSlot slot = createSlot("phase1-cancel-redis-repair", 1);
        appointmentSlotService.update().set("available_quota", 0).eq("id", slot.getId()).update();
        AppointmentOrder order = createReservedOrder(slot);
        seedAcceptedRedisReservation(slot.getId(), USER_ID);
        UserHolder.saveUser(testUser());

        Result cancelResult = appointmentOrderService.cancelOrder(order.getId());
        AppointmentConsistencyRepair pending = appointmentConsistencyRepairService.query()
                .eq("order_id", order.getId())
                .one();

        assertTrue(Boolean.TRUE.equals(cancelResult.getSuccess()));
        assertEquals(AppointmentOrderStatus.CANCELED.getCode(), appointmentOrderService.getById(order.getId()).getStatus());
        assertEquals(1, appointmentSlotService.getById(slot.getId()).getAvailableQuota());
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId()));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(
                RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(USER_ID)
        )));
        assertEquals(AppointmentConsistencyRepairServiceImpl.STATUS_PENDING, pending.getStatus());
        assertEquals(1, pending.getAttempts());

        assertTrue(appointmentConsistencyRepairService.repairCancelRedisState(order.getId()));
        assertTrue(appointmentConsistencyRepairService.repairCancelRedisState(order.getId()),
                "a completed repair must be idempotent");

        AppointmentConsistencyRepair completed = appointmentConsistencyRepairService.getById(pending.getId());
        assertEquals(AppointmentConsistencyRepairServiceImpl.STATUS_COMPLETED, completed.getStatus());
        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId()));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(
                RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(USER_ID)
        )));
    }

    private AppointmentSlot createSlot(String title, int quota) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(SERVICE_POINT_ID);
        slot.setTitle(title);
        slot.setDescription("Phase 1 consistency repair acceptance");
        slot.setTotalQuota(quota);
        slot.setAvailableQuota(quota);
        slot.setStartTime(LocalDateTime.now().plusHours(1));
        slot.setEndTime(LocalDateTime.now().plusHours(2));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        return slot;
    }

    private AppointmentOrder createReservedOrder(AppointmentSlot slot) {
        AppointmentOrder order = new AppointmentOrder();
        order.setId(ORDER_ID.incrementAndGet());
        order.setUserId(USER_ID);
        order.setSlotId(slot.getId());
        order.setServicePointId(slot.getServicePointId());
        order.setStatus(AppointmentOrderStatus.RESERVED.getCode());
        order.setCreateTime(LocalDateTime.now());
        appointmentOrderService.save(order);
        createdOrderIds.add(order.getId());
        return order;
    }

    private void seedAcceptedRedisReservation(Long slotId, long userId) {
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, "0");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, String.valueOf(userId));
    }

    private UserDTO testUser() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setNickName("phase1-repair-user");
        user.setRole("student");
        return user;
    }
}
