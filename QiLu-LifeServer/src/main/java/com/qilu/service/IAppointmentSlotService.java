package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.entity.AppointmentSlot;

public interface IAppointmentSlotService extends IService<AppointmentSlot> {

    Result queryByServicePointId(Long servicePointId);

    Result queryAdminPage(Integer current, Long servicePointId, Integer status);

    Result saveAppointmentSlot(AppointmentSlot appointmentSlot);

    Result updateAppointmentSlot(AppointmentSlot appointmentSlot);

    Result closeAppointmentSlot(Long id);

    Result openAppointmentSlot(Long id);

    Result syncQuotaToRedis(Long id);

    Result deleteAppointmentSlot(Long id);
}
