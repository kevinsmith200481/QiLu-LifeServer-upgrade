package com.qilu.acceptance;

import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.enums.AppointmentOrderStatus;
import com.qilu.service.IAppointmentConsistencyRepairService;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IOperationLogService;
import com.qilu.service.impl.AppointmentConsistencyRepairServiceImpl;
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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@EnabledIfSystemProperty(named = "acceptance.appointment-phase1", matches = "true")
class AppointmentCancelRaceAcceptanceTest {

    private static final long SERVICE_POINT_ID = 990001L;
    private static final long USER_ID = 9_600_001L;
    private static final AtomicLong ORDER_ID = new AtomicLong(System.currentTimeMillis() * 100_000L);

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private IAppointmentOrderService appointmentOrderService;

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
            appointmentSlotService.removeById(slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + slotId);
        }
        createdOrderIds.clear();
        createdSlotIds.clear();
    }

    @Test
    void sameOrderConcurrentCancelOneHundredTimesReleasesQuotaOnce() throws Exception {
        AppointmentSlot slot = createReservedSlot();
        AppointmentOrder order = createReservedOrder(slot);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(100);
        Queue<String> unexpectedErrors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    UserHolder.saveUser(testUser());
                    Result result = appointmentOrderService.cancelOrder(order.getId());
                    if (!Boolean.TRUE.equals(result.getSuccess())) {
                        unexpectedErrors.add(result.getErrorMsg());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    unexpectedErrors.add("interrupted");
                } finally {
                    UserHolder.removeUser();
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        executor.shutdownNow();

        AppointmentOrder canceled = appointmentOrderService.getById(order.getId());
        assertTrue(unexpectedErrors.isEmpty(), "unexpected cancel errors: " + unexpectedErrors);
        assertEquals(AppointmentOrderStatus.CANCELED.getCode(), canceled.getStatus());
        assertEquals(1, appointmentSlotService.getById(slot.getId()).getAvailableQuota());
        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId()));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(
                RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(USER_ID)
        )));
        assertEquals(1, appointmentConsistencyRepairService.query()
                .eq("order_id", order.getId())
                .eq("repair_type", AppointmentConsistencyRepairServiceImpl.TYPE_CANCEL_REDIS_RELEASE)
                .count());
        assertEquals(AppointmentConsistencyRepairServiceImpl.STATUS_COMPLETED,
                appointmentConsistencyRepairService.query().eq("order_id", order.getId()).one().getStatus());
        assertEquals(1, operationLogService.query()
                .eq("business_type", "APPOINTMENT")
                .eq("business_id", order.getId())
                .count());
        verify(appointmentNotificationService, times(1)).publish(org.mockito.ArgumentMatchers.any());
    }

    private AppointmentSlot createReservedSlot() {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(SERVICE_POINT_ID);
        slot.setTitle("phase1-cancel-race");
        slot.setDescription("Phase 1 cancel race acceptance");
        slot.setTotalQuota(1);
        slot.setAvailableQuota(0);
        slot.setStartTime(LocalDateTime.now().plusHours(1));
        slot.setEndTime(LocalDateTime.now().plusHours(2));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(), "0");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(USER_ID));
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

    private UserDTO testUser() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setNickName("phase1-cancel-user");
        user.setRole("student");
        return user;
    }
}
