package com.qilu.acceptance;

import com.qilu.dto.AppointmentEvent;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.entity.OperationLog;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.entity.ServicePoint;
import com.qilu.enums.AppointmentOrderStatus;
import com.qilu.mapper.AppointmentSlotMapper;
import com.qilu.controller.AdminOperationLogController;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.IAppointmentConsistencyRepairService;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IOperationLogService;
import com.qilu.service.IServicePointService;
import com.qilu.service.impl.AppointmentNotificationServiceImpl;
import com.qilu.utils.RedisConstants;
import com.qilu.utils.UserHolder;
import com.qilu.vo.AppointmentOrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@SpringBootTest
@EnabledIfSystemProperty(named = "acceptance.appointment-processing", matches = "true")
class AppointmentOrderProcessingAcceptanceTest {

    private static final long MANAGER_A_ID = 9_300_001L;
    private static final long MANAGER_B_ID = 9_300_002L;
    private static final long ADMIN_ID = 9_300_003L;
    private static final long STUDENT_A_ID = 9_300_004L;
    private static final long STUDENT_B_ID = 9_300_005L;
    private static final AtomicLong ORDER_ID = new AtomicLong(System.currentTimeMillis() * 1000L);

    @Resource
    private IServicePointService servicePointService;

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private AppointmentSlotMapper appointmentSlotMapper;

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @Resource
    private IAppointmentConsistencyRepairService appointmentConsistencyRepairService;

    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private AdminOperationLogController adminOperationLogController;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private IAppointmentNotificationService appointmentNotificationService;

    private final List<Long> createdOrderIds = new ArrayList<>();
    private final List<Long> createdSlotIds = new ArrayList<>();
    private final List<Long> createdServicePointIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
        for (Long orderId : createdOrderIds) {
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_REMINDER_KEY + orderId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_CANCEL_RELEASE_KEY + orderId);
            appointmentConsistencyRepairService.remove(
                    appointmentConsistencyRepairService.query().eq("order_id", orderId).getWrapper()
            );
            operationLogService.remove(operationLogService.query()
                    .eq("business_type", "APPOINTMENT")
                    .eq("business_id", orderId)
                    .getWrapper());
            appointmentOrderService.removeById(orderId);
        }
        for (Long slotId : createdSlotIds) {
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + slotId);
            appointmentSlotService.removeById(slotId);
        }
        for (Long servicePointId : createdServicePointIds) {
            servicePointService.removeById(servicePointId);
        }
    }

    @Test
    void studentCanOnlyQueryOwnAppointmentDetail() {
        ServicePoint point = createServicePoint("appointment-processing-student-scope", MANAGER_A_ID);
        AppointmentSlot slot = createSlot(point.getId());
        AppointmentOrder ownOrder = createOrder(STUDENT_A_ID, slot, AppointmentOrderStatus.RESERVED);
        AppointmentOrder otherOrder = createOrder(STUDENT_B_ID, slot, AppointmentOrderStatus.RESERVED);

        UserHolder.saveUser(user(STUDENT_A_ID, "student"));
        Result ownResult = appointmentOrderService.queryMyOrderDetail(ownOrder.getId());
        Result otherResult = appointmentOrderService.queryMyOrderDetail(otherOrder.getId());

        assertTrue(Boolean.TRUE.equals(ownResult.getSuccess()));
        assertEquals(ownOrder.getId(), ((AppointmentOrderVO) ownResult.getData()).getId());
        assertFalse(Boolean.TRUE.equals(otherResult.getSuccess()));
        assertEquals("Appointment order not found", otherResult.getErrorMsg());
    }

    @Test
    void adminAndManagerCanOnlyProcessScopedOrders() {
        ServicePoint ownPoint = createServicePoint("appointment-processing-own", MANAGER_A_ID);
        ServicePoint otherPoint = createServicePoint("appointment-processing-other", MANAGER_B_ID);
        AppointmentOrder ownOrder = createOrder(STUDENT_A_ID, createSlot(ownPoint.getId()), AppointmentOrderStatus.RESERVED);
        AppointmentOrder otherOrder = createOrder(STUDENT_B_ID, createSlot(otherPoint.getId()), AppointmentOrderStatus.RESERVED);

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result ownResult = appointmentOrderService.finishOrder(ownOrder.getId(), null, null);
        Result otherResult = appointmentOrderService.finishOrder(otherOrder.getId(), null, null);

        assertTrue(Boolean.TRUE.equals(ownResult.getSuccess()));
        assertEquals(AppointmentOrderStatus.FINISHED.getCode(), appointmentOrderService.getById(ownOrder.getId()).getStatus());
        assertFalse(Boolean.TRUE.equals(otherResult.getSuccess()));
        assertEquals("No permission to manage this appointment order", otherResult.getErrorMsg());
        assertEquals(AppointmentOrderStatus.RESERVED.getCode(), appointmentOrderService.getById(otherOrder.getId()).getStatus());

        UserHolder.saveUser(user(ADMIN_ID, "admin"));
        Result adminResult = appointmentOrderService.finishOrder(otherOrder.getId(), null, null);

        assertTrue(Boolean.TRUE.equals(adminResult.getSuccess()));
        assertEquals(AppointmentOrderStatus.FINISHED.getCode(), appointmentOrderService.getById(otherOrder.getId()).getStatus());
    }

    @Test
    void reservedToFinishedPublishesStudentMessage() {
        ServicePoint point = createServicePoint("appointment-processing-finished-message", MANAGER_A_ID);
        AppointmentOrder order = createOrder(STUDENT_A_ID, createSlot(point.getId()), AppointmentOrderStatus.RESERVED);

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result result = appointmentOrderService.finishOrder(order.getId(), "completed", "internal");

        ArgumentCaptor<AppointmentEvent> captor = ArgumentCaptor.forClass(AppointmentEvent.class);
        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        verify(appointmentNotificationService).publish(captor.capture());
        AppointmentEvent event = captor.getValue();
        assertEquals(AppointmentNotificationServiceImpl.FINISHED, event.getEventType());
        assertEquals(order.getId(), event.getOrderId());
        assertEquals(STUDENT_A_ID, event.getUserId());
        OperationLog log = appointmentAuditLog(order.getId());
        assertEquals(MANAGER_A_ID, log.getUserId());
        assertEquals("RESERVED", log.getBeforeStatus());
        assertEquals("FINISHED", log.getAfterStatus());
        assertEquals("remark: completed; internalRemark: internal", log.getRemarkSummary());
    }

    @Test
    void reservedToNoShowPublishesStudentMessageAndSavesRemark() {
        ServicePoint point = createServicePoint("appointment-processing-no-show-message", MANAGER_A_ID);
        AppointmentOrder order = createOrder(STUDENT_A_ID, createSlot(point.getId()), AppointmentOrderStatus.RESERVED);

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result result = appointmentOrderService.markNoShow(order.getId(), "student did not arrive", "call record checked");

        ArgumentCaptor<AppointmentEvent> captor = ArgumentCaptor.forClass(AppointmentEvent.class);
        AppointmentOrder updated = appointmentOrderService.getById(order.getId());
        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertEquals(AppointmentOrderStatus.NO_SHOW.getCode(), updated.getStatus());
        assertEquals("student did not arrive", updated.getRemark());
        assertEquals("call record checked", updated.getInternalRemark());
        verify(appointmentNotificationService).publish(captor.capture());
        AppointmentEvent event = captor.getValue();
        assertEquals(AppointmentNotificationServiceImpl.NO_SHOW, event.getEventType());
        assertEquals("student did not arrive", event.getRemark());
        assertEquals(STUDENT_A_ID, event.getUserId());
        OperationLog log = appointmentAuditLog(order.getId());
        assertEquals(MANAGER_A_ID, log.getUserId());
        assertEquals("RESERVED", log.getBeforeStatus());
        assertEquals("NO_SHOW", log.getAfterStatus());
        assertEquals("remark: student did not arrive; internalRemark: call record checked", log.getRemarkSummary());
    }

    @Test
    void canceledFinishedAndExpiredOrdersCannotBeProcessedAgain() {
        ServicePoint point = createServicePoint("appointment-processing-terminal-status", MANAGER_A_ID);
        AppointmentSlot slot = createSlot(point.getId());
        List<AppointmentOrderStatus> terminalStatuses = List.of(
                AppointmentOrderStatus.CANCELED,
                AppointmentOrderStatus.FINISHED,
                AppointmentOrderStatus.EXPIRED
        );

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));

        for (AppointmentOrderStatus status : terminalStatuses) {
            AppointmentOrder order = createOrder(STUDENT_A_ID, slot, status);

            Result finishResult = appointmentOrderService.finishOrder(order.getId(), null, null);
            Result noShowResult = appointmentOrderService.markNoShow(order.getId(), null, null);

            assertFalse(Boolean.TRUE.equals(finishResult.getSuccess()));
            assertEquals("Only reserved appointments can be updated", finishResult.getErrorMsg());
            assertFalse(Boolean.TRUE.equals(noShowResult.getSuccess()));
            assertEquals("Only reserved appointments can be updated", noShowResult.getErrorMsg());
            assertEquals(status.getCode(), appointmentOrderService.getById(order.getId()).getStatus());
        }
    }

    @Test
    void cancelAndExpireWriteAppointmentAuditLogsQueryableByOrder() {
        ServicePoint point = createServicePoint("appointment-processing-audit", MANAGER_A_ID);
        AppointmentOrder cancelOrder = createOrder(STUDENT_A_ID, createSlot(point.getId()), AppointmentOrderStatus.RESERVED);
        AppointmentOrder expireOrder = createOrder(STUDENT_A_ID, createExpiredSlot(point.getId()), AppointmentOrderStatus.RESERVED);

        UserHolder.saveUser(user(STUDENT_A_ID, "student"));
        Result cancelResult = appointmentOrderService.cancelOrder(cancelOrder.getId());

        UserHolder.removeUser();
        int expiredCount = appointmentOrderService.expireReservedOrders();

        OperationLog cancelLog = appointmentAuditLog(cancelOrder.getId());
        OperationLog expireLog = appointmentAuditLog(expireOrder.getId());
        Result pageResult = adminOperationLogController.queryOperationLogPage(1, null, cancelOrder.getId(), null);

        assertTrue(Boolean.TRUE.equals(cancelResult.getSuccess()));
        assertTrue(expiredCount >= 1);
        assertEquals(STUDENT_A_ID, cancelLog.getUserId());
        assertEquals("student", cancelLog.getUserRole());
        assertEquals("RESERVED", cancelLog.getBeforeStatus());
        assertEquals("CANCELED", cancelLog.getAfterStatus());
        assertEquals("system", expireLog.getUserRole());
        assertEquals("RESERVED", expireLog.getBeforeStatus());
        assertEquals("EXPIRED", expireLog.getAfterStatus());
        assertTrue(Boolean.TRUE.equals(pageResult.getSuccess()));
        assertEquals(1L, pageResult.getTotal());
    }

    @Test
    void upcomingReminderPublishesOnceWithRedisDedupKey() {
        ServicePoint point = createServicePoint("appointment-processing-reminder", MANAGER_A_ID);
        AppointmentOrder soonOrder = createOrder(STUDENT_A_ID, createSlotStartingIn(point.getId(), 20), AppointmentOrderStatus.RESERVED);
        createOrder(STUDENT_B_ID, createSlotStartingIn(point.getId(), 120), AppointmentOrderStatus.RESERVED);

        int firstCount = appointmentOrderService.sendUpcomingReminders();
        int secondCount = appointmentOrderService.sendUpcomingReminders();

        ArgumentCaptor<AppointmentEvent> captor = ArgumentCaptor.forClass(AppointmentEvent.class);
        assertEquals(1, firstCount);
        assertEquals(0, secondCount);
        verify(appointmentNotificationService, times(1)).publish(captor.capture());
        AppointmentEvent event = captor.getValue();
        assertEquals(AppointmentNotificationServiceImpl.REMINDER, event.getEventType());
        assertEquals(soonOrder.getId(), event.getOrderId());
        assertEquals(STUDENT_A_ID, event.getUserId());
        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_REMINDER_KEY + soonOrder.getId()));
    }

    @Test
    void activeSlotCanBeReservedAfterStartBeforeEnd() {
        ServicePoint point = createServicePoint("appointment-processing-active-slot", MANAGER_A_ID);
        AppointmentSlot slot = createActiveSlot(point.getId());
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(), "5");

        UserHolder.saveUser(user(STUDENT_A_ID, "student"));
        Result result = appointmentOrderService.reserveSlot(slot.getId());

        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertTrue(result.getData() instanceof String);
        Long orderId = Long.valueOf(String.valueOf(result.getData()));
        createdOrderIds.add(orderId);
        waitForOrderCreated(orderId);
        AppointmentOrder order = appointmentOrderService.getById(orderId);
        assertNotNull(order);
        assertEquals(STUDENT_A_ID, order.getUserId());
        assertEquals(AppointmentOrderStatus.RESERVED.getCode(), order.getStatus());
        assertEquals(4, appointmentSlotService.getById(slot.getId()).getAvailableQuota());
        assertEquals("4", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId()));
    }

    @Test
    void appointmentOrderJsonKeepsLongIdAsString() throws Exception {
        long unsafeOrderId = 203706175680675971L;
        AppointmentOrderVO vo = new AppointmentOrderVO();
        vo.setId(unsafeOrderId);
        vo.setOrderId(unsafeOrderId);

        String json = new ObjectMapper().writeValueAsString(vo);

        assertTrue(json.contains("\"id\":\"203706175680675971\""));
        assertTrue(json.contains("\"orderId\":\"203706175680675971\""));
    }

    @Test
    void adminDeleteReservedOrderRemovesRecordAndReleasesQuota() {
        ServicePoint point = createServicePoint("appointment-processing-delete", MANAGER_A_ID);
        AppointmentSlot slot = createSlot(point.getId());
        AppointmentOrder order = createOrder(STUDENT_A_ID, slot, AppointmentOrderStatus.RESERVED);
        appointmentSlotService.update().set("available_quota", 4).eq("id", slot.getId()).update();
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(), "4");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(STUDENT_A_ID));

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result result = appointmentOrderService.deleteAdminOrder(order.getId());

        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertEquals(null, appointmentOrderService.getById(order.getId()));
        assertEquals(5, appointmentSlotService.getById(slot.getId()).getAvailableQuota());
        assertEquals("5", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId()));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(STUDENT_A_ID))));
        OperationLog log = appointmentAuditLog(order.getId());
        assertEquals("Delete appointment order", log.getOperation());
        assertEquals("RESERVED", log.getBeforeStatus());
    }

    @Test
    void studentDeleteOwnFutureOrderRemovesRecordAndReleasesQuota() {
        ServicePoint point = createServicePoint("appointment-processing-student-delete", MANAGER_A_ID);
        AppointmentSlot slot = createSlot(point.getId());
        AppointmentOrder order = createOrder(STUDENT_A_ID, slot, AppointmentOrderStatus.RESERVED);
        appointmentSlotService.update().set("available_quota", 4).eq("id", slot.getId()).update();
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(), "4");
        stringRedisTemplate.opsForSet().add(RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(STUDENT_A_ID));

        UserHolder.saveUser(user(STUDENT_A_ID, "student"));
        Result result = appointmentOrderService.deleteOrder(order.getId());

        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertEquals(null, appointmentOrderService.getById(order.getId()));
        assertEquals(5, appointmentSlotService.getById(slot.getId()).getAvailableQuota());
        assertEquals("5", stringRedisTemplate.opsForValue().get(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId()));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(), String.valueOf(STUDENT_A_ID))));
    }

    @Test
    void ongoingReservedAppointmentCannotBeDeleted() {
        ServicePoint point = createServicePoint("appointment-processing-ongoing-delete", MANAGER_A_ID);
        AppointmentSlot slot = createActiveSlot(point.getId());
        AppointmentOrder order = createOrder(STUDENT_A_ID, slot, AppointmentOrderStatus.RESERVED);

        UserHolder.saveUser(user(STUDENT_A_ID, "student"));
        Result studentResult = appointmentOrderService.deleteOrder(order.getId());

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result adminResult = appointmentOrderService.deleteAdminOrder(order.getId());

        assertFalse(Boolean.TRUE.equals(studentResult.getSuccess()));
        assertEquals("Ongoing appointments cannot be deleted", studentResult.getErrorMsg());
        assertFalse(Boolean.TRUE.equals(adminResult.getSuccess()));
        assertEquals("Ongoing appointments cannot be deleted", adminResult.getErrorMsg());
        assertNotNull(appointmentOrderService.getById(order.getId()));
    }

    private ServicePoint createServicePoint(String name, Long managerId) {
        ServicePoint point = new ServicePoint();
        point.setName(name);
        point.setCategoryId(4L);
        point.setManagerId(managerId);
        point.setArea("Acceptance");
        point.setAddress(name + " address");
        point.setX(117.11);
        point.setY(36.68);
        point.setOpenHours("09:00-18:00");
        point.setDescription("Created by appointment processing acceptance test");
        point.setStatus(1);
        point.setScore(45);
        point.setServiceCount(0);
        servicePointService.save(point);
        createdServicePointIds.add(point.getId());
        return point;
    }

    private AppointmentSlot createSlot(Long servicePointId) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(servicePointId);
        slot.setTitle("appointment-processing-slot");
        slot.setDescription("Created by appointment processing acceptance test");
        slot.setTotalQuota(5);
        slot.setAvailableQuota(5);
        slot.setStartTime(LocalDateTime.now().plusDays(2));
        slot.setEndTime(LocalDateTime.now().plusDays(2).plusHours(1));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        return slot;
    }

    private AppointmentSlot createExpiredSlot(Long servicePointId) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(servicePointId);
        slot.setTitle("appointment-processing-expired-slot");
        slot.setDescription("Created by appointment processing acceptance test");
        slot.setTotalQuota(5);
        slot.setAvailableQuota(5);
        slot.setStartTime(LocalDateTime.now().minusDays(1).minusHours(1));
        slot.setEndTime(LocalDateTime.now().minusDays(1));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        return slot;
    }

    private AppointmentSlot createActiveSlot(Long servicePointId) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(servicePointId);
        slot.setTitle("appointment-processing-active-slot");
        slot.setDescription("Created by appointment processing acceptance test");
        slot.setTotalQuota(5);
        slot.setAvailableQuota(5);
        slot.setStartTime(LocalDateTime.now().minusMinutes(10));
        slot.setEndTime(LocalDateTime.now().plusHours(2));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        return slot;
    }

    private AppointmentSlot createSlotStartingIn(Long servicePointId, int minutes) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(servicePointId);
        slot.setTitle("appointment-processing-reminder-slot");
        slot.setDescription("Created by appointment processing acceptance test");
        slot.setTotalQuota(5);
        slot.setAvailableQuota(5);
        slot.setStartTime(LocalDateTime.now().plusMinutes(minutes));
        slot.setEndTime(LocalDateTime.now().plusMinutes(minutes + 30L));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        return slot;
    }

    private AppointmentOrder createOrder(Long userId, AppointmentSlot slot, AppointmentOrderStatus status) {
        AppointmentOrder order = new AppointmentOrder();
        order.setId(ORDER_ID.incrementAndGet());
        order.setUserId(userId);
        order.setSlotId(slot.getId());
        order.setServicePointId(slot.getServicePointId());
        order.setStatus(status.getCode());
        order.setCreateTime(LocalDateTime.now());
        if (status == AppointmentOrderStatus.CANCELED) {
            order.setCancelTime(LocalDateTime.now());
        }
        if (status == AppointmentOrderStatus.FINISHED) {
            order.setFinishTime(LocalDateTime.now());
        }
        appointmentOrderService.save(order);
        if (status == AppointmentOrderStatus.RESERVED
                && slot.getEndTime() != null
                && slot.getEndTime().isAfter(LocalDateTime.now())) {
            assertEquals(1, appointmentSlotMapper.deductAppointmentQuota(slot.getId()));
            AppointmentSlot currentSlot = appointmentSlotService.getById(slot.getId());
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(),
                    String.valueOf(currentSlot.getAvailableQuota())
            );
            stringRedisTemplate.opsForSet().add(
                    RedisConstants.APPOINTMENT_ORDER_KEY + slot.getId(),
                    String.valueOf(userId)
            );
        }
        createdOrderIds.add(order.getId());
        return order;
    }

    private UserDTO user(Long id, String role) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setNickName("appointment-processing-" + id);
        user.setRole(role);
        return user;
    }

    private OperationLog appointmentAuditLog(Long orderId) {
        OperationLog log = operationLogService.query()
                .eq("business_type", "APPOINTMENT")
                .eq("business_id", orderId)
                .one();
        assertNotNull(log);
        return log;
    }

    private void waitForOrderCreated(Long orderId) {
        for (int i = 0; i < 20; i++) {
            if (appointmentOrderService.getById(orderId) != null) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
