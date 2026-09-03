package com.qilu.acceptance;

import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AppointmentSlot;
import com.qilu.entity.AppointmentOrder;
import com.qilu.enums.AppointmentPersistenceOutcome;
import com.qilu.enums.AppointmentOrderStatus;
import com.qilu.mapper.AppointmentSlotMapper;
import com.qilu.service.IAppointmentFailureLogService;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.impl.AppointmentOrderServiceImpl;
import com.qilu.utils.RedisConstants;
import com.qilu.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfSystemProperty(named = "acceptance.appointment-phase1", matches = "true")
class AppointmentQuotaRaceAcceptanceTest {

    private static final long SERVICE_POINT_ID = 990001L;
    private static final AtomicLong ORDER_ID = new AtomicLong(System.currentTimeMillis() * 100_000L);
    private static final AtomicLong USER_ID = new AtomicLong(9_500_000L);

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @Resource
    private AppointmentOrderServiceImpl appointmentOrderProcessor;

    @Resource
    private AppointmentSlotMapper appointmentSlotMapper;

    @Resource
    private IAppointmentFailureLogService appointmentFailureLogService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    @MockBean
    private IAppointmentNotificationService appointmentNotificationService;

    private final List<Long> createdSlotIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long slotId : createdSlotIds) {
            cleanupSlot(slotId);
        }
        createdSlotIds.clear();
        UserHolder.removeUser();
    }

    @Test
    void twoIndependentTransactionsCompeteForQuotaOne() throws Exception {
        Long slotId = createSlot("phase1-two-transaction-race", 1, 1, LocalDateTime.now().plusHours(1));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> transactionTemplate.execute(status -> {
                ready.countDown();
                await(start);
                return appointmentSlotMapper.deductAppointmentQuota(slotId);
            })));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        int affectedRows = futures.get(0).get(10, TimeUnit.SECONDS) + futures.get(1).get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(1, affectedRows);
        assertEquals(0, appointmentSlotService.getById(slotId).getAvailableQuota());
    }

    @Test
    void oneHundredPersistedEventsCompeteForQuotaOneAcrossOneHundredRounds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(32);
        try {
            for (int round = 0; round < 100; round++) {
                Long slotId = createSlot("phase1-db-race-" + round, 1, 1, LocalDateTime.now().plusHours(1));
                CountDownLatch start = new CountDownLatch(1);
                List<Future<AppointmentPersistenceOutcome>> futures = new ArrayList<>();
                for (int event = 0; event < 100; event++) {
                    long orderId = ORDER_ID.incrementAndGet();
                    long userId = USER_ID.incrementAndGet();
                    futures.add(executor.submit(() -> {
                        await(start);
                        return appointmentOrderProcessor.processAppointmentEvent(orderId, userId, slotId);
                    }));
                }
                start.countDown();

                int created = 0;
                for (Future<AppointmentPersistenceOutcome> future : futures) {
                    if (future.get(30, TimeUnit.SECONDS) == AppointmentPersistenceOutcome.CREATED) {
                        created++;
                    }
                }

                assertEquals(1, created, "round " + round + " must persist exactly one order");
                assertEquals(1, activeOrderCount(slotId), "round " + round + " active order count");
                assertEquals(0, appointmentSlotService.getById(slotId).getAvailableQuota(), "round " + round + " DB quota");
                cleanupSlot(slotId);
                createdSlotIds.remove(slotId);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameUserConcurrentReservationOneThousandTimesSucceedsOnce() throws Exception {
        int requestCount = 1000;
        Long slotId = createSlot("phase1-same-user-1000", requestCount, 1, LocalDateTime.now().plusHours(1));
        long userId = USER_ID.incrementAndGet();
        ExecutorService executor = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requestCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    await(start);
                    UserHolder.saveUser(testUser(userId));
                    Result result = appointmentOrderService.reserveSlot(slotId);
                    if (Boolean.TRUE.equals(result.getSuccess())) {
                        success.incrementAndGet();
                    } else if ("Duplicate appointment is not allowed".equals(result.getErrorMsg())) {
                        duplicate.incrementAndGet();
                    } else {
                        unexpected.add(result.getErrorMsg());
                    }
                } finally {
                    UserHolder.removeUser();
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdownNow();
        waitUntilActiveOrderCount(slotId, 1);

        assertEquals(1, success.get());
        assertEquals(999, duplicate.get());
        assertTrue(unexpected.isEmpty(), "unexpected errors: " + unexpected);
        assertEquals(1, activeOrderCount(slotId));
        assertEquals(requestCount - 1, appointmentSlotService.getById(slotId).getAvailableQuota());
        assertEquals(String.valueOf(requestCount - 1),
                stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId));
        assertEquals(1L, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + slotId));
    }

    @Test
    void disabledAndExpiredSlotsReturnExplicitReasonsAndCompensateRedis() {
        Long disabledSlot = createSlot("phase1-disabled", 1, 0, LocalDateTime.now().plusHours(1));
        Long expiredSlot = createSlot("phase1-expired", 1, 1, LocalDateTime.now().minusMinutes(1));
        long disabledUser = USER_ID.incrementAndGet();
        long expiredUser = USER_ID.incrementAndGet();
        seedAcceptedRedisReservation(disabledSlot, disabledUser);
        seedAcceptedRedisReservation(expiredSlot, expiredUser);

        AppointmentPersistenceOutcome disabled = appointmentOrderProcessor.processAppointmentEvent(
                ORDER_ID.incrementAndGet(), disabledUser, disabledSlot
        );
        AppointmentPersistenceOutcome expired = appointmentOrderProcessor.processAppointmentEvent(
                ORDER_ID.incrementAndGet(), expiredUser, expiredSlot
        );

        assertEquals(AppointmentPersistenceOutcome.SLOT_DISABLED, disabled);
        assertEquals(AppointmentPersistenceOutcome.SLOT_EXPIRED, expired);
        assertEquals(0, activeOrderCount(disabledSlot));
        assertEquals(0, activeOrderCount(expiredSlot));
        assertEquals(0L, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + disabledSlot));
        assertEquals(0L, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + expiredSlot));
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + disabledSlot));
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + expiredSlot));
    }

    @Test
    void databaseUniqueKeyRejectsTwoActiveOrdersButAllowsHistoryAfterTerminalState() {
        Long slotId = createSlot("phase1-active-order-unique-key", 2, 1, LocalDateTime.now().plusHours(1));
        long userId = USER_ID.incrementAndGet();
        AppointmentOrder first = buildOrder(ORDER_ID.incrementAndGet(), userId, slotId);
        AppointmentOrder second = buildOrder(ORDER_ID.incrementAndGet(), userId, slotId);

        appointmentOrderService.save(first);
        assertThrows(DuplicateKeyException.class, () -> appointmentOrderService.save(second));

        appointmentOrderService.update()
                .set("status", AppointmentOrderStatus.CANCELED.getCode())
                .eq("id", first.getId())
                .eq("status", AppointmentOrderStatus.RESERVED.getCode())
                .update();
        assertTrue(appointmentOrderService.save(second));
        assertEquals(1, activeOrderCount(slotId));
    }

    private Long createSlot(String title, int totalQuota, int status, LocalDateTime endTime) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(SERVICE_POINT_ID);
        slot.setTitle(title);
        slot.setDescription("Phase 1 appointment quota acceptance");
        slot.setTotalQuota(totalQuota);
        slot.setAvailableQuota(totalQuota);
        slot.setStartTime(endTime.minusHours(1));
        slot.setEndTime(endTime);
        slot.setStatus(status);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        stringRedisTemplate.opsForValue().set(
                RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(), String.valueOf(totalQuota)
        );
        stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId());
        return slot.getId();
    }

    private void seedAcceptedRedisReservation(Long slotId, long userId) {
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, "0");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, String.valueOf(userId));
    }

    private UserDTO testUser(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("phase1-user-" + userId);
        user.setRole("student");
        return user;
    }

    private AppointmentOrder buildOrder(long orderId, long userId, Long slotId) {
        AppointmentOrder order = new AppointmentOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSlotId(slotId);
        order.setServicePointId(SERVICE_POINT_ID);
        order.setStatus(AppointmentOrderStatus.RESERVED.getCode());
        order.setCreateTime(LocalDateTime.now());
        return order;
    }

    private long activeOrderCount(Long slotId) {
        return appointmentOrderService.query().eq("slot_id", slotId).eq("status", 1).count();
    }

    private void waitUntilActiveOrderCount(Long slotId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (activeOrderCount(slotId) == expected) {
                return;
            }
            Thread.sleep(100L);
        }
        assertEquals(expected, activeOrderCount(slotId));
    }

    private void cleanupSlot(Long slotId) {
        List<Long> orderIds = appointmentOrderService.query()
                .select("id")
                .eq("slot_id", slotId)
                .list()
                .stream()
                .map(com.qilu.entity.AppointmentOrder::getId)
                .toList();
        for (Long orderId : orderIds) {
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_CANCEL_RELEASE_KEY + orderId);
        }
        appointmentFailureLogService.remove(
                appointmentFailureLogService.query().eq("slot_id", slotId).getWrapper()
        );
        appointmentOrderService.remove(appointmentOrderService.query().eq("slot_id", slotId).getWrapper());
        appointmentSlotService.removeById(slotId);
        stringRedisTemplate.delete(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId);
        stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + slotId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating appointment race", e);
        }
    }
}
