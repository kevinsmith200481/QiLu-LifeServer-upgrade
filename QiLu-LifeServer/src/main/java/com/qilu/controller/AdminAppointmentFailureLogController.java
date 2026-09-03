package com.qilu.controller;

import com.qilu.dto.Result;
import com.qilu.service.IAppointmentFailureLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin/appointment-failure-log")
public class AdminAppointmentFailureLogController {

    @Resource
    private IAppointmentFailureLogService appointmentFailureLogService;

    @GetMapping("/page")
    public Result queryFailureLogPage(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "failureType", required = false) String failureType,
            @RequestParam(value = "status", required = false) String status) {
        return appointmentFailureLogService.queryAdminPage(current, failureType, status);
    }
}
