package com.qilu.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qilu.dto.Result;
import com.qilu.entity.OperationLog;
import com.qilu.service.IOperationLogService;
import com.qilu.utils.SystemConstants;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin/operation-log")
public class AdminOperationLogController {

    @Resource
    private IOperationLogService operationLogService;

    @GetMapping("/page")
    public Result queryOperationLogPage(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "appointmentOrderId", required = false) Long appointmentOrderId,
            @RequestParam(value = "success", required = false) Integer success) {
        Page<OperationLog> page = operationLogService.query()
                .eq(module != null && module.length() > 0, "module", module)
                .eq(appointmentOrderId != null, "business_type", "APPOINTMENT")
                .eq(appointmentOrderId != null, "business_id", appointmentOrderId)
                .eq(success != null, "success", success)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }
}
