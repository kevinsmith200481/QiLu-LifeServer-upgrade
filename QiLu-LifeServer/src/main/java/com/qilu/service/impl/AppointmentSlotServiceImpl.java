package com.qilu.service.impl;

import com.qilu.dto.AppointmentEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.entity.ServicePoint;
import com.qilu.enums.AppointmentOrderStatus;
import com.qilu.mapper.AppointmentOrderMapper;
import com.qilu.mapper.AppointmentSlotMapper;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IServicePointService;
import com.qilu.utils.CreateOnlyId;
import com.qilu.utils.RedisConstants;
import com.qilu.utils.SystemConstants;
import com.qilu.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AppointmentSlotServiceImpl extends ServiceImpl<AppointmentSlotMapper, AppointmentSlot> implements IAppointmentSlotService {

    @Resource
    private IServicePointService servicePointService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AppointmentOrderMapper appointmentOrderMapper;

    @Resource
    private IAppointmentNotificationService appointmentNotificationService;

    @Resource
    private CreateOnlyId createOnlyId;

    @Override
    public Result queryByServicePointId(Long servicePointId) {
        return Result.ok(query()
                .eq("service_point_id", servicePointId)
                .eq("status", 1)
                .gt("end_time", LocalDateTime.now())
                .orderByAsc("start_time")
                .list());
    }

    @Override
    public Result queryAdminPage(Integer current, Long servicePointId, Integer status) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        if (!isAdmin(user) && !isManager(user)) {
            return Result.fail("No permission to query appointment slots");
        }
        List<Long> managedServicePointIds = null;
        if (isManager(user)) {
            managedServicePointIds = servicePointService.query()
                    .eq("manager_id", user.getId())
                    .list()
                    .stream()
                    .map(ServicePoint::getId)
                    .toList();
            if (managedServicePointIds.isEmpty()) {
                return Result.ok(List.of(), 0L);
            }
            if (servicePointId != null && !managedServicePointIds.contains(servicePointId)) {
                return Result.ok(List.of(), 0L);
            }
        }
        Page<AppointmentSlot> page = query()
                .in(managedServicePointIds != null, "service_point_id", managedServicePointIds)
                .eq(servicePointId != null, "service_point_id", servicePointId)
                .eq(status != null, "status", status)
                .orderByDesc("start_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional
    public Result saveAppointmentSlot(AppointmentSlot appointmentSlot) {
        if (!canManageServicePoint(UserHolder.getUser(), appointmentSlot.getServicePointId())) {
            return Result.fail("No permission to manage this service point");
        }
        if (appointmentSlot.getStatus() == null) {
            appointmentSlot.setStatus(1);
        }
        if (appointmentSlot.getAvailableQuota() == null) {
            appointmentSlot.setAvailableQuota(appointmentSlot.getTotalQuota());
        }
        save(appointmentSlot);
        syncQuotaValue(appointmentSlot);
        return Result.ok(appointmentSlot.getId());
    }

    @Override
    @Transactional
    public Result updateAppointmentSlot(AppointmentSlot appointmentSlot) {
        if (appointmentSlot.getId() == null) {
            return Result.fail("appointment slot id is required");
        }
        AppointmentSlot oldSlot = getById(appointmentSlot.getId());
        if (oldSlot == null) {
            return Result.fail("Appointment slot not found");
        }
        if (!canManageServicePoint(UserHolder.getUser(), oldSlot.getServicePointId())) {
            return Result.fail("No permission to manage this appointment slot");
        }
        if (appointmentSlot.getServicePointId() == null) {
            appointmentSlot.setServicePointId(oldSlot.getServicePointId());
        } else if (!canManageServicePoint(UserHolder.getUser(), appointmentSlot.getServicePointId())) {
            return Result.fail("No permission to move this appointment slot to target service point");
        }
        updateById(appointmentSlot);
        AppointmentSlot newSlot = getById(appointmentSlot.getId());
        syncQuotaValue(newSlot);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result closeAppointmentSlot(Long id) {
        AppointmentSlot slot = getById(id);
        if (slot == null) {
            return Result.fail("Appointment slot not found");
        }
        if (!canManageServicePoint(UserHolder.getUser(), slot.getServicePointId())) {
            return Result.fail("No permission to manage this appointment slot");
        }
        update().set("status", 0).eq("id", id).update();
        AppointmentSlot closedSlot = getById(id);
        if (closedSlot == null || !Integer.valueOf(0).equals(closedSlot.getStatus())) {
            return Result.fail("Close appointment slot failed");
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + id, "0");
        notifyReservedUsersForClosedSlot(closedSlot);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result openAppointmentSlot(Long id) {
        AppointmentSlot slot = getById(id);
        if (slot == null) {
            return Result.fail("Appointment slot not found");
        }
        if (!canManageServicePoint(UserHolder.getUser(), slot.getServicePointId())) {
            return Result.fail("No permission to manage this appointment slot");
        }
        update().set("status", 1).eq("id", id).update();
        AppointmentSlot openedSlot = getById(id);
        if (openedSlot == null || !Integer.valueOf(1).equals(openedSlot.getStatus())) {
            return Result.fail("Open appointment slot failed");
        }
        syncQuotaValue(openedSlot);
        return Result.ok();
    }

    @Override
    public Result syncQuotaToRedis(Long id) {
        AppointmentSlot slot = getById(id);
        if (slot == null) {
            return Result.fail("Appointment slot not found");
        }
        if (!canManageServicePoint(UserHolder.getUser(), slot.getServicePointId())) {
            return Result.fail("No permission to manage this appointment slot");
        }
        syncQuotaValue(slot);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result deleteAppointmentSlot(Long id) {
        AppointmentSlot slot = getById(id);
        if (slot == null) {
            return Result.fail("Appointment slot not found");
        }
        if (!canManageServicePoint(UserHolder.getUser(), slot.getServicePointId())) {
            return Result.fail("No permission to manage this appointment slot");
        }
        if (!Integer.valueOf(0).equals(slot.getStatus())) {
            return Result.fail("Please close appointment slot before deletion");
        }
        if (hasAppointmentOrders(id)) {
            return Result.fail("Cannot delete appointment slot with appointment orders");
        }
        removeById(id);
        stringRedisTemplate.delete(RedisConstants.APPOINTMENT_QUOTA_KEY + id);
        stringRedisTemplate.delete(RedisConstants.APPOINTMENT_ORDER_KEY + id);
        return Result.ok();
    }

    private void syncQuotaValue(AppointmentSlot slot) {
        int quota = 0;
        if (slot != null && Integer.valueOf(1).equals(slot.getStatus())
                && slot.getEndTime() != null && slot.getEndTime().isAfter(LocalDateTime.now())
                && slot.getAvailableQuota() != null) {
            quota = Math.max(slot.getAvailableQuota(), 0);
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.APPOINTMENT_QUOTA_KEY + slot.getId(), String.valueOf(quota));
    }

    private boolean hasAppointmentOrders(Long slotId) {
        Long count = appointmentOrderMapper.selectCount(new LambdaQueryWrapper<AppointmentOrder>()
                .eq(AppointmentOrder::getSlotId, slotId));
        return count != null && count > 0;
    }

    private void notifyReservedUsersForClosedSlot(AppointmentSlot slot) {
        List<AppointmentOrder> orders = appointmentOrderMapper.selectList(new LambdaQueryWrapper<AppointmentOrder>()
                .eq(AppointmentOrder::getSlotId, slot.getId())
                .eq(AppointmentOrder::getStatus, AppointmentOrderStatus.RESERVED.getCode()));
        if (orders == null || orders.isEmpty()) {
            return;
        }
        ServicePoint servicePoint = servicePointService.getById(slot.getServicePointId());
        for (AppointmentOrder order : orders) {
            AppointmentEvent event = new AppointmentEvent();
            event.setEventId(String.valueOf(createOnlyId.createId("appointment-event")));
            event.setEventType(AppointmentNotificationServiceImpl.SLOT_CLOSED);
            event.setOrderId(order.getId());
            event.setUserId(order.getUserId());
            event.setSlotId(slot.getId());
            event.setServicePointId(slot.getServicePointId());
            event.setManagerId(servicePoint == null ? null : servicePoint.getManagerId());
            event.setSlotTitle(slot.getTitle());
            event.setServicePointName(servicePoint == null ? null : servicePoint.getName());
            event.setServicePointAddress(servicePoint == null ? null : servicePoint.getAddress());
            event.setStartTime(slot.getStartTime());
            event.setEndTime(slot.getEndTime());
            event.setOccurTime(java.time.LocalDateTime.now());
            appointmentNotificationService.publish(event);
        }
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

    private boolean isAdmin(UserDTO user) {
        return "admin".equals(user.getRole());
    }

    private boolean isManager(UserDTO user) {
        return "manager".equals(user.getRole());
    }
}
