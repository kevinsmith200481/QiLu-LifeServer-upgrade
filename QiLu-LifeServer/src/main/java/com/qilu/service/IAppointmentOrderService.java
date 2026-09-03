package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.entity.AppointmentOrder;

import java.time.LocalDateTime;

public interface IAppointmentOrderService extends IService<AppointmentOrder> {

    Result reserveSlot(Long slotId);

    Result queryMyOrders();

    Result queryMyOrderDetail(Long orderId);

    Result cancelOrder(Long orderId);

    Result deleteOrder(Long orderId);

    Result queryAdminPage(Integer current, Long servicePointId, Integer status, Long userId, LocalDateTime startTime, LocalDateTime endTime);

    Result queryAdminStats(Long servicePointId, Long userId, LocalDateTime startTime, LocalDateTime endTime);

    Result queryAdminDetail(Long orderId);

    Result finishOrder(Long orderId, String remark, String internalRemark);

    Result markNoShow(Long orderId, String remark, String internalRemark);

    Result deleteAdminOrder(Long orderId);

    int expireReservedOrders();

    int sendUpcomingReminders();
}
