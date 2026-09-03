package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.entity.AppointmentConsistencyRepair;

public interface IAppointmentConsistencyRepairService extends IService<AppointmentConsistencyRepair> {

    void createCancelRedisRepair(Long orderId, Long userId, Long slotId, boolean releaseRedisQuota);

    boolean repairCancelRedisState(Long orderId);

    int repairPendingTasks();
}
