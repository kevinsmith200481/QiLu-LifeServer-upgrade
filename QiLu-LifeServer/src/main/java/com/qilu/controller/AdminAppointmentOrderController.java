package com.qilu.controller;

import com.qilu.annotation.Log;
import com.qilu.dto.Result;
import com.qilu.service.IAppointmentOrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/appointment-order")
public class AdminAppointmentOrderController {

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @GetMapping("/page")
    public Result queryAppointmentOrderPage(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "servicePointId", required = false) Long servicePointId,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "startTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(value = "endTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return appointmentOrderService.queryAdminPage(current, servicePointId, status, userId, startTime, endTime);
    }

    @GetMapping("/stats")
    public Result queryAppointmentOrderStats(
            @RequestParam(value = "servicePointId", required = false) Long servicePointId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "startTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(value = "endTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return appointmentOrderService.queryAdminStats(servicePointId, userId, startTime, endTime);
    }

    @GetMapping("/{id}")
    public Result queryAppointmentOrderDetail(@PathVariable("id") Long id) {
        return appointmentOrderService.queryAdminDetail(id);
    }

    @PutMapping("/{id}/finish")
    @Log(module = "AppointmentOrder", operation = "Finish appointment order")
    public Result finishOrder(
            @PathVariable("id") Long id,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestParam(value = "internalRemark", required = false) String internalRemark) {
        return appointmentOrderService.finishOrder(id, remark, internalRemark);
    }

    @PutMapping("/{id}/no-show")
    @Log(module = "AppointmentOrder", operation = "Mark appointment no-show")
    public Result markNoShow(
            @PathVariable("id") Long id,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestParam(value = "internalRemark", required = false) String internalRemark) {
        return appointmentOrderService.markNoShow(id, remark, internalRemark);
    }

    @DeleteMapping("/{id}")
    @Log(module = "AppointmentOrder", operation = "Delete appointment order")
    public Result deleteOrder(@PathVariable("id") Long id) {
        return appointmentOrderService.deleteAdminOrder(id);
    }
}
