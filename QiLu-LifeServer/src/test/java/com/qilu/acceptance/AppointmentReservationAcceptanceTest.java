package com.qilu.acceptance;

import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.enums.AppointmentPersistenceOutcome;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.impl.AppointmentOrderServiceImpl;
import com.qilu.utils.RedisConstants;
import com.qilu.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfSystemProperty(named = "acceptance.appointment", matches = "true")
class AppointmentReservationAcceptanceTest {

    private static final long TEST_USER_BASE_ID = 9_000_000L;
    private static final long DEFAULT_SERVICE_POINT_ID = 4L;

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @Resource
    private AppointmentOrderServiceImpl appointmentOrderRecoveryService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private IAppointmentNotificationService appointmentNotificationService;

    private final List<Long> createdSlotIds = new ArrayList<>();
    private final List<String> createdStreamKeys = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long slotId : createdSlotIds) {
            appointmentOrderService.remove(appointmentOrderService.query().eq("slot_id", slotId).getWrapper());
            appointmentSlotService.removeById(slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + slotId);
        }
        for (String streamKey : createdStreamKeys) {
            stringRedisTemplate.delete(streamKey);
        }
        createdSlotIds.clear();
        createdStreamKeys.clear();
    }

    @Test
    void concurrentReservationDoesNotOversellAndAsyncPersistsOrders() throws Exception {
        int quota = 20;
        int requestCount = 120;
        Long slotId = createSlot("acceptance-concurrent", quota);

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger noQuotaCount = new AtomicInteger();
        AtomicInteger unexpectedFailureCount = new AtomicInteger();
        Queue<Object> orderIds = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < requestCount; i++) {
            long userId = TEST_USER_BASE_ID + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    UserHolder.saveUser(testUser(userId));
                    Result result = appointmentOrderService.reserveSlot(slotId);
                    if (Boolean.TRUE.equals(result.getSuccess())) {
                        successCount.incrementAndGet();
                        orderIds.add(result.getData());
                    } else if ("No available quota".equals(result.getErrorMsg())) {
                        noQuotaCount.incrementAndGet();
                    } else {
                        unexpectedFailureCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    unexpectedFailureCount.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not become ready in time");
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "reservation requests did not finish in time");
        executor.shutdownNow();

        assertEquals(quota, successCount.get(), "successful reservations must equal quota");
        assertEquals(requestCount - quota, noQuotaCount.get(), "remaining requests must be rejected by quota");
        assertEquals(0, unexpectedFailureCount.get(), "unexpected reservation failures");
        assertEquals(quota, orderIds.stream().distinct().count(), "order ids must be unique");

        waitUntilOrderCount(slotId, quota);
        assertEquals(quota, activeOrderCount(slotId), "async stream consumer must persist exactly quota orders");
        assertEquals(0, appointmentSlotService.getById(slotId).getAvailableQuota(), "DB quota must reach zero");
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId));
        assertEquals(quota, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + slotId));
    }

    @Test
    void duplicateReservationForSameUserIsRejectedBeforeSecondOrderIsCreated() throws Exception {
        Long slotId = createSlot("acceptance-duplicate", 2);
        UserDTO user = testUser(TEST_USER_BASE_ID + 10_000);

        UserHolder.saveUser(user);
        Result first = appointmentOrderService.reserveSlot(slotId);
        Result second = appointmentOrderService.reserveSlot(slotId);
        UserHolder.removeUser();

        assertTrue(Boolean.TRUE.equals(first.getSuccess()), "first reservation should succeed");
        assertNotNull(first.getData(), "first reservation should return order id");
        assertEquals(Boolean.FALSE, second.getSuccess(), "second reservation should fail");
        assertEquals("Duplicate appointment is not allowed", second.getErrorMsg());

        waitUntilOrderCount(slotId, 1);
        assertEquals(1, activeOrderCount(slotId), "only one DB order is allowed for same user and slot");
        assertEquals(1, appointmentSlotService.getById(slotId).getAvailableQuota(), "duplicate request must not consume DB quota");
        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId));
        assertEquals(1, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + slotId));
    }

    @Test
    void pendingAppointmentMessageIsRecoveredAndAcknowledged() {
        Long slotId = createSlot("acceptance-pending-recovery", 1);
        long userId = TEST_USER_BASE_ID + 20_000;
        long orderId = System.currentTimeMillis();
        String streamKey = RedisConstants.APPOINTMENT_ORDER_STREAM_KEY + ":acceptance:" + slotId;
        String groupName = "acceptance-g1";
        String consumerName = "acceptance-c1";
        createdStreamKeys.add(streamKey);

        Map<String, String> message = new HashMap<>();
        message.put("id", String.valueOf(orderId));
        message.put("userId", String.valueOf(userId));
        message.put("slotId", String.valueOf(slotId));
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, "0");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, String.valueOf(userId));
        RecordId recordId = stringRedisTemplate.opsForStream().add(streamKey, message);
        assertNotNull(recordId, "test stream message id must be created");
        stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);

        List<MapRecord<String, Object, Object>> deliveredButNotAcked = stringRedisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );

        assertNotNull(deliveredButNotAcked, "pending fault injection should deliver one stream record");
        assertEquals(1, deliveredButNotAcked.size(), "exactly one record should be moved to pending");
        assertEquals(0, activeOrderCount(slotId), "order must not be persisted before recovery");

        int recovered = appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, consumerName);

        assertEquals(1, recovered, "one pending stream record should be recovered");
        assertEquals(1, activeOrderCount(slotId), "recovered pending message must create the appointment order");
        assertEquals(0, appointmentSlotService.getById(slotId).getAvailableQuota(), "DB quota must be deducted during recovery");
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId));
        assertEquals(1, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + slotId));
        assertEquals(0, appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, consumerName),
                "acknowledged pending message must not be recovered twice");
    }

    @Test
    void stalePendingMessageFromOldConsumerIsClaimedAndRecovered() throws Exception {
        Long slotId = createSlot("acceptance-stale-pending-recovery", 1);
        long userId = TEST_USER_BASE_ID + 25_000;
        long orderId = System.currentTimeMillis();
        String streamKey = RedisConstants.APPOINTMENT_ORDER_STREAM_KEY + ":acceptance:stale:" + slotId;
        String groupName = "acceptance-g-stale";
        String oldConsumerName = "acceptance-old-consumer";
        String newConsumerName = "acceptance-new-consumer";
        createdStreamKeys.add(streamKey);

        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, "0");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, String.valueOf(userId));
        addAppointmentStreamMessage(streamKey, orderId, userId, slotId);
        stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);

        List<MapRecord<String, Object, Object>> deliveredButNotAcked = stringRedisTemplate.opsForStream().read(
                Consumer.from(groupName, oldConsumerName),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );

        assertNotNull(deliveredButNotAcked, "stale pending fault injection should deliver one stream record");
        assertEquals(1, deliveredButNotAcked.size(), "one record should be held by the old consumer");
        Thread.sleep(1200L);

        int recovered = appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, newConsumerName);

        assertEquals(1, recovered, "new consumer should claim and recover the old pending record");
        assertEquals(1, activeOrderCount(slotId), "claimed pending message must create the appointment order");
        assertEquals(0, appointmentSlotService.getById(slotId).getAvailableQuota(), "DB quota must be deducted during stale recovery");
        assertEquals(0, appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, newConsumerName),
                "acknowledged stale pending record must not be recovered twice");
    }

    @Test
    void duplicatePendingMessagesAreAcknowledgedWithoutDuplicateOrderOrQuotaDeduction() {
        Long slotId = createSlot("acceptance-duplicate-pending", 2);
        long userId = TEST_USER_BASE_ID + 30_000;
        String streamKey = RedisConstants.APPOINTMENT_ORDER_STREAM_KEY + ":acceptance:duplicate:" + slotId;
        String groupName = "acceptance-g-duplicate";
        String consumerName = "acceptance-c-duplicate";
        createdStreamKeys.add(streamKey);

        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, "1");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, String.valueOf(userId));
        addAppointmentStreamMessage(streamKey, System.currentTimeMillis(), userId, slotId);
        addAppointmentStreamMessage(streamKey, System.currentTimeMillis() + 1, userId, slotId);
        stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);

        List<MapRecord<String, Object, Object>> deliveredButNotAcked = stringRedisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                StreamReadOptions.empty().count(2),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );

        assertNotNull(deliveredButNotAcked, "duplicate fault injection should deliver stream records");
        assertEquals(2, deliveredButNotAcked.size(), "both duplicate records should be moved to pending");

        int recovered = appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, consumerName);

        assertEquals(2, recovered, "duplicate pending records should both be acknowledged");
        assertEquals(1, activeOrderCount(slotId), "duplicate stream messages must create only one active order");
        assertEquals(1, appointmentSlotService.getById(slotId).getAvailableQuota(),
                "duplicate pending message must not deduct DB quota twice");
        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId),
                "Redis quota was already deducted once by Lua and must not be changed by recovery");
        assertEquals(1, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + slotId));
        assertEquals(0, appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, consumerName),
                "acknowledged duplicate pending records must not be recovered again");
    }

    @Test
    void alreadyPersistedOrderBeforeAckIsAcknowledgedWithoutSecondQuotaDeduction() {
        Long slotId = createSlot("acceptance-written-before-ack", 1);
        long userId = TEST_USER_BASE_ID + 40_000;
        long orderId = System.currentTimeMillis();
        String streamKey = RedisConstants.APPOINTMENT_ORDER_STREAM_KEY + ":acceptance:written:" + slotId;
        String groupName = "acceptance-g-written";
        String consumerName = "acceptance-c-written";
        createdStreamKeys.add(streamKey);

        appointmentSlotService.update()
                .set("available_quota", 0)
                .eq("id", slotId)
                .update();
        AppointmentOrder existingOrder = new AppointmentOrder();
        existingOrder.setId(orderId);
        existingOrder.setUserId(userId);
        existingOrder.setSlotId(slotId);
        existingOrder.setServicePointId(DEFAULT_SERVICE_POINT_ID);
        existingOrder.setStatus(1);
        existingOrder.setCreateTime(LocalDateTime.now());
        appointmentOrderService.save(existingOrder);
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, "0");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, String.valueOf(userId));
        addAppointmentStreamMessage(streamKey, orderId, userId, slotId);
        stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);

        List<MapRecord<String, Object, Object>> deliveredButNotAcked = stringRedisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );

        assertNotNull(deliveredButNotAcked, "written-before-ack fault injection should deliver one stream record");
        assertEquals(1, deliveredButNotAcked.size(), "one record should be moved to pending");

        int recovered = appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, consumerName);

        assertEquals(1, recovered, "already persisted pending record should still be acknowledged");
        assertEquals(1, activeOrderCount(slotId), "already persisted order must not be inserted again");
        assertEquals(0, appointmentSlotService.getById(slotId).getAvailableQuota(),
                "already persisted order must not deduct DB quota again");
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId));
        assertEquals(1, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + slotId));
        assertEquals(0, appointmentOrderRecoveryService.recoverPendingAppointmentOrders(streamKey, groupName, consumerName),
                "acknowledged written-before-ack record must not be recovered again");
    }

    @Test
    void slotClosedWhileEventIsQueuedIsRejectedAndRedisReservationIsCompensated() {
        Long slotId = createSlot("acceptance-closed-while-queued", 1);
        long userId = TEST_USER_BASE_ID + 50_000;
        long orderId = System.currentTimeMillis();
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, "0");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, String.valueOf(userId));
        appointmentSlotService.update().set("status", 0).eq("id", slotId).update();

        AppointmentPersistenceOutcome outcome = appointmentOrderRecoveryService.processAppointmentEvent(orderId, userId, slotId);

        assertEquals(AppointmentPersistenceOutcome.SLOT_DISABLED, outcome);
        assertEquals(0, activeOrderCount(slotId));
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId));
        assertEquals(0L, stringRedisTemplate.opsForSet().size(RedisConstants.APPOINTMENT_ORDER_KEY + slotId));
    }

    private Long createSlot(String title, int quota) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(DEFAULT_SERVICE_POINT_ID);
        slot.setTitle(title);
        slot.setDescription("Created by appointment acceptance test");
        slot.setTotalQuota(quota);
        slot.setAvailableQuota(quota);
        slot.setStartTime(LocalDateTime.now().plusDays(1));
        slot.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(), String.valueOf(quota));
        stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId());
        return slot.getId();
    }

    private UserDTO testUser(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("acceptance-user-" + userId);
        user.setRole("student");
        return user;
    }

    private void addAppointmentStreamMessage(String streamKey, long orderId, long userId, Long slotId) {
        Map<String, String> message = new HashMap<>();
        message.put("id", String.valueOf(orderId));
        message.put("userId", String.valueOf(userId));
        message.put("slotId", String.valueOf(slotId));
        RecordId recordId = stringRedisTemplate.opsForStream().add(streamKey, message);
        assertNotNull(recordId, "test stream message id must be created");
    }

    private void waitUntilOrderCount(Long slotId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (activeOrderCount(slotId) == expected) {
                return;
            }
            Thread.sleep(100L);
        }
        assertEquals(expected, activeOrderCount(slotId), "timed out waiting for async appointment orders");
    }

    private long activeOrderCount(Long slotId) {
        return appointmentOrderService.query()
                .eq("slot_id", slotId)
                .eq("status", 1)
                .count();
    }
}
