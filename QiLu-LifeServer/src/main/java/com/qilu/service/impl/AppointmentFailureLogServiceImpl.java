package com.qilu.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.dto.AppointmentEvent;
import com.qilu.dto.Result;
import com.qilu.entity.AppointmentFailureLog;
import com.qilu.mapper.AppointmentFailureLogMapper;
import com.qilu.service.IAppointmentFailureLogService;
import com.qilu.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class AppointmentFailureLogServiceImpl extends ServiceImpl<AppointmentFailureLogMapper, AppointmentFailureLog>
        implements IAppointmentFailureLogService {

    public static final String TYPE_ASYNC_ORDER_REJECTED = "ASYNC_ORDER_REJECTED";
    public static final String TYPE_NOTIFICATION_DEAD = "NOTIFICATION_DEAD";
    public static final String STATUS_COMPENSATED = "COMPENSATED";
    public static final String STATUS_DEAD = "DEAD";

    @Resource
    private AppointmentFailureLogMapper appointmentFailureLogMapper;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void logAsyncOrderRejected(Long orderId, Long userId, Long slotId, String reason) {
        ensureTable();
        AppointmentFailureLog logItem = new AppointmentFailureLog();
        logItem.setFailureType(TYPE_ASYNC_ORDER_REJECTED);
        logItem.setStatus(STATUS_COMPENSATED);
        logItem.setOrderId(orderId);
        logItem.setUserId(userId);
        logItem.setSlotId(slotId);
        logItem.setReason(reason);
        logItem.setCreateTime(LocalDateTime.now());
        save(logItem);
    }

    @Override
    public void logNotificationDead(AppointmentEvent event, String reason) {
        ensureTable();
        if (event != null && event.getEventId() != null) {
            Long exists = query()
                    .eq("failure_type", TYPE_NOTIFICATION_DEAD)
                    .eq("event_id", event.getEventId())
                    .count();
            if (exists != null && exists > 0) {
                return;
            }
        }
        AppointmentFailureLog logItem = new AppointmentFailureLog();
        logItem.setFailureType(TYPE_NOTIFICATION_DEAD);
        logItem.setStatus(STATUS_DEAD);
        logItem.setEventId(event == null ? null : event.getEventId());
        logItem.setOrderId(event == null ? null : event.getOrderId());
        logItem.setUserId(event == null ? null : event.getUserId());
        logItem.setSlotId(event == null ? null : event.getSlotId());
        logItem.setServicePointId(event == null ? null : event.getServicePointId());
        logItem.setReason(reason);
        logItem.setPayload(toJson(event));
        logItem.setCreateTime(LocalDateTime.now());
        save(logItem);
    }

    @Override
    public Result queryAdminPage(Integer current, String failureType, String status) {
        ensureTable();
        Page<AppointmentFailureLog> page = query()
                .eq(failureType != null && !failureType.isBlank(), "failure_type", failureType)
                .eq(status != null && !status.isBlank(), "status", status)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    private void ensureTable() {
        appointmentFailureLogMapper.createTable();
    }

    private String toJson(AppointmentEvent event) {
        if (event == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("Serialize appointment event failed, eventId={}", event.getEventId(), e);
            return String.valueOf(event);
        }
    }
}
