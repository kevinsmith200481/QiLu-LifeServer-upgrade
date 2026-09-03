package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.AppointmentEvent;
import com.qilu.dto.Result;
import com.qilu.entity.AppointmentFailureLog;

public interface IAppointmentFailureLogService extends IService<AppointmentFailureLog> {

    void logAsyncOrderRejected(Long orderId, Long userId, Long slotId, String reason);

    void logNotificationDead(AppointmentEvent event, String reason);

    Result queryAdminPage(Integer current, String failureType, String status);
}
