package com.qilu.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.AppointmentEvent;
import com.qilu.dto.UserDTO;
import com.qilu.entity.ServicePoint;
import com.qilu.acceptance.AcceptanceFaultInjector;
import com.qilu.enums.AppointmentPersistenceOutcome;
import com.qilu.enums.AppointmentOrderStatus;
import com.qilu.mapper.AppointmentOrderMapper;
import com.qilu.mapper.AppointmentSlotMapper;
import com.qilu.service.IAppointmentConsistencyRepairService;
import com.qilu.service.IAppointmentFailureLogService;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IOperationLogService;
import com.qilu.service.IServicePointService;
import com.qilu.utils.CreateOnlyId;
import com.qilu.utils.SystemConstants;
import com.qilu.vo.AppointmentOrderVO;
import com.qilu.vo.AppointmentOrderStatsVO;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Service;
import com.qilu.dto.Result;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.utils.RedisConstants;
import com.qilu.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AppointmentOrderServiceImpl extends ServiceImpl<AppointmentOrderMapper, AppointmentOrder> implements IAppointmentOrderService {

    private static final String GROUP_NAME = "g1";
    private static final long REMINDER_WINDOW_MINUTES = 30L;
    private static final long REMINDER_DEDUP_DAYS = 7L;
    private static final int PENDING_RECOVERY_BATCH_SIZE = 10;
    private static final Duration STALE_PENDING_MIN_IDLE = Duration.ofSeconds(1);
    private final ExecutorService appointmentOrderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "appointment-order-consumer");
        thread.setDaemon(true);
        return thread;
    });
    private static final DefaultRedisScript<Long> RESERVE_SLOT_SCRIPT;
    private volatile boolean running = true;
    private String consumerName;

    static {
        RESERVE_SLOT_SCRIPT = new DefaultRedisScript<>();
        RESERVE_SLOT_SCRIPT.setLocation(new ClassPathResource("reserve_slot.lua"));
        RESERVE_SLOT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private AppointmentSlotMapper appointmentSlotMapper;

    @Resource
    private IAppointmentConsistencyRepairService appointmentConsistencyRepairService;

    @Resource
    private AcceptanceFaultInjector acceptanceFaultInjector;

    @Resource
    private IServicePointService servicePointService;

    @Resource
    private IAppointmentNotificationService appointmentNotificationService;

    @Resource
    private IAppointmentFailureLogService appointmentFailureLogService;

    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private CreateOnlyId createOnlyId;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Value("${server.port:unknown}")
    private String serverPort;

    @Value("${qilu.appointment.consumer.enabled:true}")
    private boolean appointmentConsumerEnabled;

    @PostConstruct
    private void init() {
        consumerName = buildConsumerName();
        initStreamGroup();
        if (appointmentConsumerEnabled) {
            appointmentOrderExecutor.submit(new AppointmentOrderHandler());
        }
    }

    @PreDestroy
    private void destroy() {
        running = false;
        appointmentOrderExecutor.shutdownNow();
    }

    private void initStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(RedisConstants.APPOINTMENT_ORDER_STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
        } catch (RuntimeException e) {
            try {
                stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
                    connection.streamCommands().xGroupCreate(
                            RedisConstants.APPOINTMENT_ORDER_STREAM_KEY.getBytes(),
                            GROUP_NAME,
                            ReadOffset.from("0"),
                            true
                    );
                    return null;
                });
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public Result reserveSlot(Long slotId) {
        Long userId = UserHolder.getUser().getId();
        AppointmentSlot slot = appointmentSlotService.getById(slotId);
        if (slot == null || !Integer.valueOf(1).equals(slot.getStatus())) {
            return Result.fail("Appointment slot not available");
        }
        if (isSlotExpired(slot)) {
            return Result.fail("Appointment slot has expired");
        }
        long orderId = createOnlyId.createId("appointment-order");
        Long result = stringRedisTemplate.execute(
                RESERVE_SLOT_SCRIPT,
                Collections.emptyList(),
                slotId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int code = result == null ? -1 : result.intValue();
        if (code != 0) {
            return Result.fail(code == 1 ? "No available quota" : "Duplicate appointment is not allowed");
        }
        return Result.ok(String.valueOf(orderId));
    }

    @Override
    public Result queryMyOrders() {
        Long userId = UserHolder.getUser().getId();
        List<AppointmentOrder> orders = query().eq("user_id", userId).orderByDesc("create_time").list();
        return Result.ok(toOrderVOList(orders));
    }

    @Override
    public Result queryMyOrderDetail(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        AppointmentOrder order = getById(orderId);
        if (order == null || !user.getId().equals(order.getUserId())) {
            return Result.fail("Appointment order not found");
        }
        return Result.ok(toOrderVO(order));
    }

    @Override
    public Result cancelOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        AppointmentOrder order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("Appointment order not found");
        }
        if (Integer.valueOf(AppointmentOrderStatus.CANCELED.getCode()).equals(order.getStatus())) {
            return Result.ok();
        }
        if (!Integer.valueOf(AppointmentOrderStatus.RESERVED.getCode()).equals(order.getStatus())) {
            return Result.fail("Only reserved appointments can be canceled");
        }
        Boolean transitioned = transactionTemplate.execute(status -> {
            int canceledRows = baseMapper.cancelReservedOrder(orderId, userId, LocalDateTime.now());
            if (canceledRows == 0) {
                return false;
            }
            int releasedRows = appointmentSlotMapper.releaseAppointmentQuota(order.getSlotId());
            if (releasedRows != 1) {
                throw new IllegalStateException("Release appointment DB quota failed");
            }
            /*
             * Persist the repair intent in the same transaction as the cancel.
             * A process crash after commit can therefore never lose the Redis repair.
             */
            appointmentConsistencyRepairService.createCancelRedisRepair(
                    orderId, userId, order.getSlotId(), true
            );
            return true;
        });
        if (!Boolean.TRUE.equals(transitioned)) {
            AppointmentOrder current = getById(orderId);
            if (current != null && Integer.valueOf(AppointmentOrderStatus.CANCELED.getCode()).equals(current.getStatus())) {
                return Result.ok();
            }
            return Result.fail("Cancel appointment failed");
        }
        AppointmentOrder canceledOrder = getById(orderId);
        appointmentConsistencyRepairService.repairCancelRedisState(orderId);
        recordAppointmentOrderAudit(order.getId(), "Cancel appointment order", order.getStatus(), AppointmentOrderStatus.CANCELED.getCode(), null, null);
        publishAppointmentEvent(canceledOrder, AppointmentNotificationServiceImpl.CANCELED);
        return Result.ok();
    }

    @Override
    public Result deleteOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        AppointmentOrder order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("Appointment order not found");
        }
        return deleteOrderRecord(order);
    }

    @Override
    public Result queryAdminPage(Integer current, Long servicePointId, Integer status, Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        if (!isAdmin(user) && !isManager(user)) {
            return Result.fail("No permission to query appointment orders");
        }
        List<Long> managedServicePointIds = resolveManagedServicePointIds(user, servicePointId);
        if (managedServicePointIds != null && managedServicePointIds.isEmpty()) {
            return Result.ok(List.of(), 0L);
        }
        Page<AppointmentOrder> page = query()
                .in(managedServicePointIds != null, "service_point_id", managedServicePointIds)
                .eq(servicePointId != null, "service_point_id", servicePointId)
                .eq(status != null, "status", status)
                .eq(userId != null, "user_id", userId)
                .ge(startTime != null, "create_time", startTime)
                .le(endTime != null, "create_time", endTime)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(toOrderVOList(page.getRecords(), true), page.getTotal());
    }

    @Override
    public Result queryAdminStats(Long servicePointId, Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        if (!isAdmin(user) && !isManager(user)) {
            return Result.fail("No permission to query appointment orders");
        }
        List<Long> managedServicePointIds = resolveManagedServicePointIds(user, servicePointId);
        AppointmentOrderStatsVO stats = new AppointmentOrderStatsVO();
        if (managedServicePointIds != null && managedServicePointIds.isEmpty()) {
            stats.setPending(0L);
            stats.setToday(0L);
            stats.setFinished(0L);
            stats.setAbnormal(0L);
            return Result.ok(stats);
        }
        stats.setPending(countAdminOrders(managedServicePointIds, servicePointId, userId, startTime, endTime,
                AppointmentOrderStatus.RESERVED.getCode()));
        stats.setToday(countAdminOrders(managedServicePointIds, servicePointId, userId,
                LocalDate.now().atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay(), null));
        stats.setFinished(countAdminOrders(managedServicePointIds, servicePointId, userId, startTime, endTime,
                AppointmentOrderStatus.FINISHED.getCode()));
        stats.setAbnormal(query()
                .in(managedServicePointIds != null, "service_point_id", managedServicePointIds)
                .eq(servicePointId != null, "service_point_id", servicePointId)
                .eq(userId != null, "user_id", userId)
                .ge(startTime != null, "create_time", startTime)
                .le(endTime != null, "create_time", endTime)
                .in("status", List.of(
                        AppointmentOrderStatus.CANCELED.getCode(),
                        AppointmentOrderStatus.EXPIRED.getCode(),
                        AppointmentOrderStatus.NO_SHOW.getCode()))
                .count());
        return Result.ok(stats);
    }

    @Override
    public Result queryAdminDetail(Long orderId) {
        UserDTO user = UserHolder.getUser();
        AppointmentOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("Appointment order not found");
        }
        if (!canManageServicePoint(user, order.getServicePointId())) {
            return Result.fail("No permission to manage this appointment order");
        }
        return Result.ok(toOrderVO(order, true));
    }

    @Override
    public Result finishOrder(Long orderId, String remark, String internalRemark) {
        return updateManagedOrderStatus(orderId, AppointmentOrderStatus.FINISHED, AppointmentNotificationServiceImpl.FINISHED, remark, internalRemark);
    }

    @Override
    public Result markNoShow(Long orderId, String remark, String internalRemark) {
        return updateManagedOrderStatus(orderId, AppointmentOrderStatus.NO_SHOW, AppointmentNotificationServiceImpl.NO_SHOW, remark, internalRemark);
    }

    @Override
    public Result deleteAdminOrder(Long orderId) {
        UserDTO user = UserHolder.getUser();
        AppointmentOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("Appointment order not found");
        }
        if (!canManageServicePoint(user, order.getServicePointId())) {
            return Result.fail("No permission to manage this appointment order");
        }
        return deleteOrderRecord(order);
    }

    private Result deleteOrderRecord(AppointmentOrder order) {
        if (isAppointmentInProgress(order)) {
            return Result.fail("Ongoing appointments cannot be deleted");
        }
        Long orderId = order.getId();
        Result result = transactionTemplate.execute(status -> {
            if (Integer.valueOf(AppointmentOrderStatus.RESERVED.getCode()).equals(order.getStatus())) {
                int releasedRows = appointmentSlotMapper.releaseAppointmentQuota(order.getSlotId());
                if (releasedRows != 1) {
                    throw new IllegalStateException("Release appointment DB quota before delete failed");
                }
            }
            recordAppointmentOrderAudit(orderId, "Delete appointment order", order.getStatus(), null, null, null);
            removeById(orderId);
            if (getById(orderId) != null) {
                status.setRollbackOnly();
                return Result.fail("Delete appointment order failed");
            }
            return Result.ok();
        });
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            return result == null ? Result.fail("Delete appointment order failed") : result;
        }
        cleanupDeletedOrderCache(order);
        return Result.ok();
    }

    @Override
    public int expireReservedOrders() {
        List<AppointmentOrder> orders = query()
                .eq("status", AppointmentOrderStatus.RESERVED.getCode())
                .list();
        int count = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AppointmentOrder order : orders) {
            AppointmentSlot slot = appointmentSlotService.getById(order.getSlotId());
            if (slot == null || slot.getEndTime() == null || slot.getEndTime().isAfter(now)) {
                continue;
            }
            boolean updated = update()
                    .set("status", AppointmentOrderStatus.EXPIRED.getCode())
                    .eq("id", order.getId())
                    .eq("status", AppointmentOrderStatus.RESERVED.getCode())
                    .update();
            AppointmentOrder expiredOrder = getById(order.getId());
            if ((updated || (expiredOrder != null && Integer.valueOf(AppointmentOrderStatus.EXPIRED.getCode()).equals(expiredOrder.getStatus())))
                    && expiredOrder != null
                    && Integer.valueOf(AppointmentOrderStatus.EXPIRED.getCode()).equals(expiredOrder.getStatus())) {
                count++;
                recordAppointmentOrderAudit(order.getId(), "Expire appointment order", order.getStatus(), AppointmentOrderStatus.EXPIRED.getCode(), null, null);
                publishAppointmentEvent(expiredOrder, AppointmentNotificationServiceImpl.EXPIRED);
            }
        }
        return count;
    }

    @Override
    public int sendUpcomingReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusMinutes(REMINDER_WINDOW_MINUTES);
        List<AppointmentOrder> orders = query()
                .eq("status", AppointmentOrderStatus.RESERVED.getCode())
                .list();
        int count = 0;
        for (AppointmentOrder order : orders) {
            AppointmentSlot slot = order.getSlotId() == null ? null : appointmentSlotService.getById(order.getSlotId());
            if (slot == null || !Integer.valueOf(1).equals(slot.getStatus()) || slot.getStartTime() == null) {
                continue;
            }
            if (!slot.getStartTime().isAfter(now) || slot.getStartTime().isAfter(deadline)) {
                continue;
            }
            String dedupKey = RedisConstants.APPOINTMENT_REMINDER_KEY + order.getId();
            Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", REMINDER_DEDUP_DAYS, TimeUnit.DAYS);
            if (!Boolean.TRUE.equals(locked)) {
                continue;
            }
            try {
                publishAppointmentEvent(order, AppointmentNotificationServiceImpl.REMINDER, "appointment-reminder-30m-" + order.getId());
                count++;
            } catch (RuntimeException e) {
                stringRedisTemplate.delete(dedupKey);
                throw e;
            }
        }
        return count;
    }

    private class AppointmentOrderHandler implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, consumerName),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(RedisConstants.APPOINTMENT_ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        recoverPendingAppointmentOrders();
                        continue;
                    }
                    MapRecord<String, Object, Object> record = records.get(0);
                    createAppointmentOrder(record.getValue());
                    acceptanceFaultInjector.haltAfterAppointmentPersistBeforeAck();
                    stringRedisTemplate.opsForStream().acknowledge(RedisConstants.APPOINTMENT_ORDER_STREAM_KEY, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    if (!running) {
                        break;
                    }
                    log.error("Handle appointment order failed", e);
                    recoverPendingAppointmentOrders();
                }
            }
        }
    }

    /**
     * Recover appointment messages that were delivered to the consumer but not acknowledged.
     *
     * @return number of pending stream records acknowledged after recovery
     */
    public int recoverPendingAppointmentOrders() {
        return recoverPendingAppointmentOrders(
                RedisConstants.APPOINTMENT_ORDER_STREAM_KEY,
                GROUP_NAME,
                consumerName
        );
    }

    /**
     * Recover pending records from a specific stream group. The overload keeps the production
     * recovery path deterministic while allowing acceptance tests to use an isolated stream.
     */
    public int recoverPendingAppointmentOrders(String streamKey, String groupName, String consumerName) {
        int recoveredCount = 0;
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(groupName, consumerName),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(streamKey, ReadOffset.from("0"))
                );
                if (records == null || records.isEmpty()) {
                    break;
                }
                MapRecord<String, Object, Object> record = records.get(0);
                createAppointmentOrder(record.getValue());
                stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                recoveredCount++;
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("Handle pending appointment order failed", e);
                return recoveredCount;
            }
        }
        return recoveredCount + recoverStalePendingAppointmentOrders(streamKey, groupName, consumerName);
    }

    private int recoverStalePendingAppointmentOrders(String streamKey, String groupName, String consumerName) {
        int recoveredCount = 0;
        while (running) {
            try {
                PendingMessages pendingMessages = stringRedisTemplate.opsForStream()
                        .pending(streamKey, groupName, Range.unbounded(), PENDING_RECOVERY_BATCH_SIZE);
                if (pendingMessages == null || pendingMessages.isEmpty()) {
                    break;
                }
                List<RecordId> recordIds = new ArrayList<>();
                for (PendingMessage pendingMessage : pendingMessages) {
                    if (consumerName.equals(pendingMessage.getConsumerName())) {
                        continue;
                    }
                    if (pendingMessage.getElapsedTimeSinceLastDelivery().compareTo(STALE_PENDING_MIN_IDLE) >= 0) {
                        recordIds.add(pendingMessage.getId());
                    }
                }
                if (recordIds.isEmpty()) {
                    break;
                }
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().claim(
                        streamKey,
                        groupName,
                        consumerName,
                        STALE_PENDING_MIN_IDLE,
                        recordIds.toArray(new RecordId[0])
                );
                if (records == null || records.isEmpty()) {
                    break;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    createAppointmentOrder(record.getValue());
                    stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                    recoveredCount++;
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("Handle stale pending appointment order failed", e);
                break;
            }
        }
        return recoveredCount;
    }

    private void createAppointmentOrder(Map<Object, Object> value) {
        Long orderId = Long.valueOf(value.get("id").toString());
        Long userId = Long.valueOf(value.get("userId").toString());
        Long slotId = Long.valueOf(value.get("slotId").toString());
        processAppointmentEvent(orderId, userId, slotId);
    }

    /**
     * Processes one Redis Stream event and returns a deterministic persistence outcome.
     * Public visibility allows the concurrency acceptance suite to exercise the same
     * production path without reaching into private implementation details.
     */
    public AppointmentPersistenceOutcome processAppointmentEvent(Long orderId, Long userId, Long slotId) {
        AppointmentOrder existingOrder = getById(orderId);
        if (existingOrder != null) {
            return AppointmentPersistenceOutcome.ALREADY_PERSISTED;
        }
        RLock lock = redissonClient.getLock("lock:appointment-order:" + userId);
        boolean isLock;
        try {
            isLock = lock.tryLock(3, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for appointment order lock", e);
        }
        if (!isLock) {
            throw new IllegalStateException("Appointment order lock is busy, retry from Redis Stream pending list");
        }
        try {
            existingOrder = getById(orderId);
            if (existingOrder != null) {
                return AppointmentPersistenceOutcome.ALREADY_PERSISTED;
            }
            AppointmentPersistenceResult persistenceResult = createOrderInTransaction(orderId, userId, slotId);
            if (persistenceResult.outcome == AppointmentPersistenceOutcome.CREATED) {
                publishAppointmentEvent(
                        buildReservedOrder(orderId, userId, slotId, persistenceResult.slot),
                        AppointmentNotificationServiceImpl.RESERVED
                );
                return persistenceResult.outcome;
            }
            if (requiresRedisReservationCompensation(persistenceResult.outcome)) {
                compensateRejectedReservation(orderId, slotId, userId, persistenceResult.outcome.name());
            }
            return persistenceResult.outcome;
        } finally {
            lock.unlock();
        }
    }

    private AppointmentPersistenceResult createOrderInTransaction(Long orderId, Long userId, Long slotId) {
        try {
            return transactionTemplate.execute(status -> {
                if (activeOrderExists(userId, slotId)) {
                    return AppointmentPersistenceResult.rejected(AppointmentPersistenceOutcome.DUPLICATE_ACTIVE_ORDER);
                }
                AppointmentSlot slot = appointmentSlotService.getById(slotId);
                int affectedRows = appointmentSlotMapper.deductAppointmentQuota(slotId);
                if (affectedRows != 1) {
                    return AppointmentPersistenceResult.rejected(classifyReservationRejection(userId, slotId));
                }
                AppointmentOrder order = buildReservedOrder(orderId, userId, slotId, slot);
                if (!save(order)) {
                    throw new IllegalStateException("Create appointment order failed");
                }
                // Acceptance-only fault point: the quota update and order insert
                // must roll back together when this throws.
                acceptanceFaultInjector.afterDatabaseOperation();
                return AppointmentPersistenceResult.created(slot);
            });
        } catch (DuplicateKeyException e) {
            AppointmentOrder existingOrder = getById(orderId);
            AppointmentPersistenceOutcome outcome = existingOrder == null
                    ? AppointmentPersistenceOutcome.DUPLICATE_ACTIVE_ORDER
                    : AppointmentPersistenceOutcome.ALREADY_PERSISTED;
            return AppointmentPersistenceResult.rejected(outcome);
        }
    }

    private AppointmentPersistenceOutcome classifyReservationRejection(Long userId, Long slotId) {
        if (activeOrderExists(userId, slotId)) {
            return AppointmentPersistenceOutcome.DUPLICATE_ACTIVE_ORDER;
        }
        AppointmentSlot slot = appointmentSlotService.getById(slotId);
        if (slot == null || !Integer.valueOf(1).equals(slot.getStatus())) {
            return AppointmentPersistenceOutcome.SLOT_DISABLED;
        }
        if (isSlotExpired(slot)) {
            return AppointmentPersistenceOutcome.SLOT_EXPIRED;
        }
        return AppointmentPersistenceOutcome.NO_QUOTA;
    }

    private boolean activeOrderExists(Long userId, Long slotId) {
        Long count = query()
                .eq("user_id", userId)
                .eq("slot_id", slotId)
                .eq("status", AppointmentOrderStatus.RESERVED.getCode())
                .count();
        return count != null && count > 0;
    }

    private boolean requiresRedisReservationCompensation(AppointmentPersistenceOutcome outcome) {
        return outcome == AppointmentPersistenceOutcome.NO_QUOTA
                || outcome == AppointmentPersistenceOutcome.SLOT_DISABLED
                || outcome == AppointmentPersistenceOutcome.SLOT_EXPIRED;
    }

    private static class AppointmentPersistenceResult {
        private final AppointmentPersistenceOutcome outcome;
        private final AppointmentSlot slot;

        private AppointmentPersistenceResult(AppointmentPersistenceOutcome outcome, AppointmentSlot slot) {
            this.outcome = outcome;
            this.slot = slot;
        }

        private static AppointmentPersistenceResult created(AppointmentSlot slot) {
            return new AppointmentPersistenceResult(AppointmentPersistenceOutcome.CREATED, slot);
        }

        private static AppointmentPersistenceResult rejected(AppointmentPersistenceOutcome outcome) {
            return new AppointmentPersistenceResult(outcome, null);
        }
    }

    private AppointmentOrder buildReservedOrder(Long orderId, Long userId, Long slotId, AppointmentSlot slot) {
        if (slot == null) {
            throw new IllegalStateException("Appointment slot disappeared after quota deduction");
        }
        AppointmentOrder order = new AppointmentOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSlotId(slotId);
        order.setServicePointId(slot.getServicePointId());
        order.setStatus(AppointmentOrderStatus.RESERVED.getCode());
        order.setCreateTime(LocalDateTime.now());
        return order;
    }

    private void compensateRejectedReservation(Long orderId, Long slotId, Long userId, String reason) {
        stringRedisTemplate.opsForSet().remove(RedisConstants.APPOINTMENT_ORDER_KEY + slotId, userId.toString());
        syncRedisQuotaFromDb(slotId);
        appointmentFailureLogService.logAsyncOrderRejected(orderId, userId, slotId, reason);
    }

    private void syncRedisQuotaFromDb(Long slotId) {
        AppointmentSlot slot = appointmentSlotService.getById(slotId);
        int quota = 0;
        if (slot != null && Integer.valueOf(1).equals(slot.getStatus())
                && !isSlotExpired(slot)
                && slot.getAvailableQuota() != null) {
            quota = Math.max(slot.getAvailableQuota(), 0);
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId, String.valueOf(quota));
    }

    private void cleanupDeletedOrderCache(AppointmentOrder order) {
        if (order == null || order.getId() == null) {
            return;
        }
        stringRedisTemplate.delete(RedisConstants.APPOINTMENT_REMINDER_KEY + order.getId());
        if (order.getSlotId() != null && order.getUserId() != null) {
            stringRedisTemplate.opsForSet().remove(RedisConstants.APPOINTMENT_ORDER_KEY + order.getSlotId(), order.getUserId().toString());
            syncRedisQuotaFromDb(order.getSlotId());
        }
    }

    private boolean isSlotExpired(AppointmentSlot slot) {
        return slot == null || slot.getEndTime() == null || !slot.getEndTime().isAfter(LocalDateTime.now());
    }

    private boolean isAppointmentInProgress(AppointmentOrder order) {
        if (order == null || !Integer.valueOf(AppointmentOrderStatus.RESERVED.getCode()).equals(order.getStatus())) {
            return false;
        }
        AppointmentSlot slot = order.getSlotId() == null ? null : appointmentSlotService.getById(order.getSlotId());
        if (slot == null || slot.getStartTime() == null || slot.getEndTime() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return !slot.getStartTime().isAfter(now) && slot.getEndTime().isAfter(now);
    }

    private Result updateManagedOrderStatus(Long orderId, AppointmentOrderStatus targetStatus, String eventType, String remark, String internalRemark) {
        UserDTO user = UserHolder.getUser();
        AppointmentOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("Appointment order not found");
        }
        if (!canManageServicePoint(user, order.getServicePointId())) {
            return Result.fail("No permission to manage this appointment order");
        }
        if (!Integer.valueOf(AppointmentOrderStatus.RESERVED.getCode()).equals(order.getStatus())) {
            return Result.fail("Only reserved appointments can be updated");
        }
        AppointmentSlot slot = appointmentSlotService.getById(order.getSlotId());
        if (targetStatus == AppointmentOrderStatus.FINISHED
                && (slot == null || slot.getEndTime() == null || !slot.getEndTime().isAfter(LocalDateTime.now()))) {
            return Result.fail("Expired appointments cannot be finished");
        }
        String normalizedRemark = normalizeRemark(remark);
        String normalizedInternalRemark = normalizeRemark(internalRemark);
        boolean updated = update()
                .set("status", targetStatus.getCode())
                .set(targetStatus == AppointmentOrderStatus.FINISHED, "finish_time", LocalDateTime.now())
                .set(normalizedRemark != null, "remark", normalizedRemark)
                .set(normalizedInternalRemark != null, "internal_remark", normalizedInternalRemark)
                .eq("id", orderId)
                .eq("status", AppointmentOrderStatus.RESERVED.getCode())
                .update();
        AppointmentOrder updatedOrder = getById(orderId);
        if (!updated && (updatedOrder == null || !Integer.valueOf(targetStatus.getCode()).equals(updatedOrder.getStatus()))) {
            return Result.fail("Update appointment order failed");
        }
        if (updatedOrder == null || !Integer.valueOf(targetStatus.getCode()).equals(updatedOrder.getStatus())) {
            return Result.fail("Update appointment order failed");
        }
        order = updatedOrder;
        order.setStatus(targetStatus.getCode());
        if (targetStatus == AppointmentOrderStatus.FINISHED) {
            order.setFinishTime(LocalDateTime.now());
        }
        if (normalizedRemark != null) {
            order.setRemark(normalizedRemark);
        }
        if (normalizedInternalRemark != null) {
            order.setInternalRemark(normalizedInternalRemark);
        }
        recordAppointmentOrderAudit(orderId, appointmentAuditOperation(targetStatus), AppointmentOrderStatus.RESERVED.getCode(), targetStatus.getCode(), normalizedRemark, normalizedInternalRemark);
        publishAppointmentEvent(order, eventType);
        return Result.ok();
    }

    private String appointmentAuditOperation(AppointmentOrderStatus targetStatus) {
        if (targetStatus == AppointmentOrderStatus.FINISHED) {
            return "Finish appointment order";
        }
        if (targetStatus == AppointmentOrderStatus.NO_SHOW) {
            return "Mark appointment no-show";
        }
        return "Update appointment order";
    }

    private void recordAppointmentOrderAudit(
            Long orderId,
            String operation,
            Integer beforeStatus,
            Integer afterStatus,
            String remark,
            String internalRemark) {
        try {
            operationLogService.saveAppointmentOrderAudit(orderId, operation, beforeStatus, afterStatus, remark, internalRemark);
        } catch (Exception e) {
            log.warn("Save appointment order audit log failed, orderId={}", orderId, e);
        }
    }

    private void publishAppointmentEvent(AppointmentOrder order, String eventType) {
        publishAppointmentEvent(order, eventType, String.valueOf(createOnlyId.createId("appointment-event")));
    }

    private void publishAppointmentEvent(AppointmentOrder order, String eventType, String eventId) {
        AppointmentSlot slot = order.getSlotId() == null ? null : appointmentSlotService.getById(order.getSlotId());
        ServicePoint servicePoint = null;
        Long servicePointId = order.getServicePointId();
        if (servicePointId == null && slot != null) {
            servicePointId = slot.getServicePointId();
        }
        if (servicePointId != null) {
            servicePoint = servicePointService.getById(servicePointId);
        }
        AppointmentEvent event = new AppointmentEvent();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setOrderId(order.getId());
        event.setUserId(order.getUserId());
        event.setSlotId(order.getSlotId());
        event.setServicePointId(servicePointId);
        event.setManagerId(servicePoint == null ? null : servicePoint.getManagerId());
        event.setSlotTitle(slot == null ? null : slot.getTitle());
        event.setServicePointName(servicePoint == null ? null : servicePoint.getName());
        event.setServicePointAddress(servicePoint == null ? null : servicePoint.getAddress());
        event.setStartTime(slot == null ? null : slot.getStartTime());
        event.setEndTime(slot == null ? null : slot.getEndTime());
        event.setRemark(order.getRemark());
        event.setOccurTime(LocalDateTime.now());
        appointmentNotificationService.publish(event);
    }

    private AppointmentOrderVO toOrderVO(AppointmentOrder order) {
        return toOrderVO(order, false);
    }

    private AppointmentOrderVO toOrderVO(AppointmentOrder order, boolean includeInternalRemark) {
        return toOrderVOList(Collections.singletonList(order), includeInternalRemark).get(0);
    }

    private List<AppointmentOrderVO> toOrderVOList(List<AppointmentOrder> orders) {
        return toOrderVOList(orders, false);
    }

    private List<AppointmentOrderVO> toOrderVOList(List<AppointmentOrder> orders, boolean includeInternalRemark) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        Set<Long> slotIds = orders.stream()
                .map(AppointmentOrder::getSlotId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AppointmentSlot> slotMap = slotIds.isEmpty() ? new HashMap<>() : appointmentSlotService.listByIds(slotIds)
                .stream()
                .collect(Collectors.toMap(AppointmentSlot::getId, Function.identity(), (left, right) -> left));
        Set<Long> servicePointIds = orders.stream()
                .map(AppointmentOrder::getServicePointId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        servicePointIds.addAll(slotMap.values().stream()
                .map(AppointmentSlot::getServicePointId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<Long, ServicePoint> servicePointMap = servicePointIds.isEmpty() ? new HashMap<>() : servicePointService.listByIds(servicePointIds)
                .stream()
                .collect(Collectors.toMap(ServicePoint::getId, Function.identity(), (left, right) -> left));
        return orders.stream()
                .map(order -> toOrderVO(order, slotMap, servicePointMap, includeInternalRemark))
                .collect(Collectors.toList());
    }

    private AppointmentOrderVO toOrderVO(
            AppointmentOrder order,
            Map<Long, AppointmentSlot> slotMap,
            Map<Long, ServicePoint> servicePointMap,
            boolean includeInternalRemark) {
        AppointmentSlot slot = slotMap.get(order.getSlotId());
        Long servicePointId = order.getServicePointId();
        if (servicePointId == null && slot != null) {
            servicePointId = slot.getServicePointId();
        }
        ServicePoint servicePoint = servicePointMap.get(servicePointId);
        AppointmentOrderStatus status = AppointmentOrderStatus.of(order.getStatus());
        AppointmentOrderVO vo = new AppointmentOrderVO();
        vo.setOrderId(order.getId());
        vo.setId(order.getId());
        vo.setUserId(order.getUserId());
        vo.setSlotId(order.getSlotId());
        vo.setServicePointId(servicePointId);
        vo.setStatus(order.getStatus());
        vo.setStatusText(status == null ? "Unknown" : status.getDesc());
        vo.setRemark(order.getRemark());
        if (includeInternalRemark) {
            vo.setInternalRemark(order.getInternalRemark());
        }
        vo.setSlotTitle(slot == null ? null : slot.getTitle());
        vo.setSlotDescription(slot == null ? null : slot.getDescription());
        vo.setSlotStatus(slot == null ? null : slot.getStatus());
        vo.setServicePointName(servicePoint == null ? null : servicePoint.getName());
        vo.setServicePointAddress(servicePoint == null ? null : servicePoint.getAddress());
        vo.setStartTime(slot == null ? null : slot.getStartTime());
        vo.setEndTime(slot == null ? null : slot.getEndTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setFinishTime(order.getFinishTime());
        return vo;
    }

    private boolean canManageServicePoint(UserDTO user, Long servicePointId) {
        if (user == null || servicePointId == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (!isManager(user)) {
            return false;
        }
        ServicePoint servicePoint = servicePointService.getById(servicePointId);
        return servicePoint != null && user.getId().equals(servicePoint.getManagerId());
    }

    private List<Long> resolveManagedServicePointIds(UserDTO user, Long servicePointId) {
        if (!isManager(user)) {
            return null;
        }
        List<Long> managedServicePointIds = servicePointService.query()
                .eq("manager_id", user.getId())
                .list()
                .stream()
                .map(ServicePoint::getId)
                .toList();
        if (managedServicePointIds.isEmpty()) {
            return List.of();
        }
        if (servicePointId != null && !managedServicePointIds.contains(servicePointId)) {
            return List.of();
        }
        return managedServicePointIds;
    }

    private Long countAdminOrders(
            List<Long> managedServicePointIds,
            Long servicePointId,
            Long userId,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer status) {
        return query()
                .in(managedServicePointIds != null, "service_point_id", managedServicePointIds)
                .eq(servicePointId != null, "service_point_id", servicePointId)
                .eq(userId != null, "user_id", userId)
                .ge(startTime != null, "create_time", startTime)
                .lt(endTime != null, "create_time", endTime)
                .eq(status != null, "status", status)
                .count();
    }

    private boolean isAdmin(UserDTO user) {
        return "admin".equals(user.getRole());
    }

    private boolean isManager(UserDTO user) {
        return "manager".equals(user.getRole());
    }

    private String normalizeRemark(String remark) {
        if (remark == null) {
            return null;
        }
        String value = remark.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private String buildConsumerName() {
        String host = "unknown-host";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
        }
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        String pid = runtimeName == null ? "unknown-pid" : runtimeName.split("@")[0];
        return "appointment-" + serverPort + "-" + host + "-" + pid;
    }
}
