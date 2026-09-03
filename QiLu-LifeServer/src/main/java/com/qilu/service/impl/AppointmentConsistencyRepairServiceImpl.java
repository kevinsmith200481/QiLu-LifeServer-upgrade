package com.qilu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.acceptance.AcceptanceFaultInjector;
import com.qilu.entity.AppointmentConsistencyRepair;
import com.qilu.mapper.AppointmentConsistencyRepairMapper;
import com.qilu.service.IAppointmentConsistencyRepairService;
import com.qilu.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AppointmentConsistencyRepairServiceImpl
        extends ServiceImpl<AppointmentConsistencyRepairMapper, AppointmentConsistencyRepair>
        implements IAppointmentConsistencyRepairService {

    public static final String TYPE_CANCEL_REDIS_RELEASE = "CANCEL_REDIS_RELEASE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    private static final long RELEASE_MARKER_TTL_SECONDS = TimeUnit.DAYS.toSeconds(30);
    private static final int RETRY_BATCH_SIZE = 100;
    private static final DefaultRedisScript<Long> RELEASE_APPOINTMENT_SCRIPT;

    static {
        RELEASE_APPOINTMENT_SCRIPT = new DefaultRedisScript<>();
        RELEASE_APPOINTMENT_SCRIPT.setLocation(new ClassPathResource("release_appointment.lua"));
        RELEASE_APPOINTMENT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private AppointmentConsistencyRepairMapper repairMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AcceptanceFaultInjector acceptanceFaultInjector;

    @Override
    public void createCancelRedisRepair(Long orderId, Long userId, Long slotId, boolean releaseRedisQuota) {
        repairMapper.insertCancelRedisRepair(orderId, userId, slotId, releaseRedisQuota);
    }

    @Override
    public boolean repairCancelRedisState(Long orderId) {
        AppointmentConsistencyRepair task = query()
                .eq("order_id", orderId)
                .eq("repair_type", TYPE_CANCEL_REDIS_RELEASE)
                .one();
        if (task == null || STATUS_COMPLETED.equals(task.getStatus())) {
            return true;
        }
        try {
            acceptanceFaultInjector.beforeAppointmentCancelRedisUpdate();
            Long result = stringRedisTemplate.execute(
                    RELEASE_APPOINTMENT_SCRIPT,
                    List.of(
                            RedisConstants.APPOINTMENT_QUOTA_KEY + task.getSlotId(),
                            RedisConstants.APPOINTMENT_ORDER_KEY + task.getSlotId(),
                            RedisConstants.APPOINTMENT_CANCEL_RELEASE_KEY + task.getOrderId()
                    ),
                    String.valueOf(task.getUserId()),
                    String.valueOf(Integer.valueOf(1).equals(task.getReleaseRedisQuota()) ? 1 : 0),
                    String.valueOf(RELEASE_MARKER_TTL_SECONDS)
            );
            if (result == null || result < 0) {
                throw new IllegalStateException("Appointment quota cache is unavailable for cancel repair");
            }
            markCompleted(task.getId());
            return true;
        } catch (RuntimeException e) {
            markRetry(task.getId(), e.getMessage());
            log.warn("Appointment cancel Redis repair deferred, orderId={}", orderId, e);
            return false;
        }
    }

    @Override
    public int repairPendingTasks() {
        List<AppointmentConsistencyRepair> tasks = query()
                .eq("repair_type", TYPE_CANCEL_REDIS_RELEASE)
                .eq("status", STATUS_PENDING)
                .le("next_retry_time", LocalDateTime.now())
                .orderByAsc("id")
                .last("LIMIT " + RETRY_BATCH_SIZE)
                .list();
        int repaired = 0;
        for (AppointmentConsistencyRepair task : tasks) {
            if (repairCancelRedisState(task.getOrderId())) {
                repaired++;
            }
        }
        return repaired;
    }

    private void markCompleted(Long taskId) {
        update()
                .set("status", STATUS_COMPLETED)
                .set("last_error", null)
                .set("next_retry_time", null)
                .eq("id", taskId)
                .eq("status", STATUS_PENDING)
                .update();
    }

    private void markRetry(Long taskId, String error) {
        String normalizedError = error == null ? "unknown Redis repair error" : error;
        if (normalizedError.length() > 512) {
            normalizedError = normalizedError.substring(0, 512);
        }
        update()
                .setSql("attempts = attempts + 1")
                .set("last_error", normalizedError)
                .set("next_retry_time", LocalDateTime.now().plusSeconds(5))
                .eq("id", taskId)
                .eq("status", STATUS_PENDING)
                .update();
    }
}
