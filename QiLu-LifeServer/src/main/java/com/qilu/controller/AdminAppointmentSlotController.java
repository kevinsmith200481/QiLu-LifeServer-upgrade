package com.qilu.controller;

import com.qilu.annotation.Log;
import com.qilu.dto.Result;
import com.qilu.entity.AppointmentSlot;
import com.qilu.service.IAppointmentSlotService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin/appointment-slot")
public class AdminAppointmentSlotController {

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @GetMapping("/page")
    public Result queryAppointmentSlotPage(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "servicePointId", required = false) Long servicePointId,
            @RequestParam(value = "status", required = false) Integer status) {
        return appointmentSlotService.queryAdminPage(current, servicePointId, status);
    }

    @PostMapping
    @Log(module = "AppointmentSlot", operation = "Create appointment slot")
    public Result saveAppointmentSlot(@RequestBody AppointmentSlot appointmentSlot) {
        return appointmentSlotService.saveAppointmentSlot(appointmentSlot);
    }

    @PutMapping
    @Log(module = "AppointmentSlot", operation = "Update appointment slot")
    public Result updateAppointmentSlot(@RequestBody AppointmentSlot appointmentSlot) {
        return appointmentSlotService.updateAppointmentSlot(appointmentSlot);
    }

    @PutMapping("/{id}/close")
    @Log(module = "AppointmentSlot", operation = "Close appointment slot")
    public Result closeAppointmentSlot(@PathVariable("id") Long id) {
        return appointmentSlotService.closeAppointmentSlot(id);
    }

    @PutMapping("/{id}/open")
    @Log(module = "AppointmentSlot", operation = "Open appointment slot")
    public Result openAppointmentSlot(@PathVariable("id") Long id) {
        return appointmentSlotService.openAppointmentSlot(id);
    }

    @PutMapping("/{id}/sync-quota")
    @Log(module = "AppointmentSlot", operation = "Sync appointment quota")
    public Result syncQuotaToRedis(@PathVariable("id") Long id) {
        return appointmentSlotService.syncQuotaToRedis(id);
    }

    @DeleteMapping("/{id}")
    @Log(module = "AppointmentSlot", operation = "Delete appointment slot")
    public Result deleteAppointmentSlot(@PathVariable("id") Long id) {
        return appointmentSlotService.deleteAppointmentSlot(id);
    }
}
