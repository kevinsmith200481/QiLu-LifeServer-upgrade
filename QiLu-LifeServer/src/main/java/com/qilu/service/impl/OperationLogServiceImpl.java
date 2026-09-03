package com.qilu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.UserDTO;
import com.qilu.entity.OperationLog;
import com.qilu.mapper.OperationLogMapper;
import com.qilu.service.IOperationLogService;
import com.qilu.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog> implements IOperationLogService {

    private static final int REMARK_SUMMARY_MAX_LENGTH = 512;

    @Override
    public void saveAppointmentOrderAudit(
            Long orderId,
            String operation,
            Integer beforeStatus,
            Integer afterStatus,
            String remark,
            String internalRemark) {
        UserDTO user = UserHolder.getUser();
        OperationLog log = new OperationLog()
                .setUserId(user == null ? null : user.getId())
                .setUserRole(user == null ? "system" : user.getRole())
                .setModule("AppointmentOrder")
                .setOperation(operation)
                .setBusinessType("APPOINTMENT")
                .setBusinessId(orderId)
                .setBeforeStatus(statusText(beforeStatus))
                .setAfterStatus(statusText(afterStatus))
                .setRemarkSummary(buildRemarkSummary(remark, internalRemark))
                .setSuccess(1)
                .setCreateTime(LocalDateTime.now());
        save(log);
    }

    private String statusText(Integer status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case 1:
                return "RESERVED";
            case 2:
                return "CANCELED";
            case 3:
                return "FINISHED";
            case 4:
                return "EXPIRED";
            case 5:
                return "NO_SHOW";
            default:
                return String.valueOf(status);
        }
    }

    private String buildRemarkSummary(String remark, String internalRemark) {
        String publicRemark = normalize(remark);
        String adminRemark = normalize(internalRemark);
        if (publicRemark == null && adminRemark == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        if (publicRemark != null) {
            builder.append("remark: ").append(publicRemark);
        }
        if (adminRemark != null) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append("internalRemark: ").append(adminRemark);
        }
        return truncate(builder.toString(), REMARK_SUMMARY_MAX_LENGTH);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
