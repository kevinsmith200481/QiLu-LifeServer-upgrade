package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.entity.OperationLog;

public interface IOperationLogService extends IService<OperationLog> {

    void saveAppointmentOrderAudit(
            Long orderId,
            String operation,
            Integer beforeStatus,
            Integer afterStatus,
            String remark,
            String internalRemark);
}
