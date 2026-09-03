package com.qilu.controller;

import com.qilu.dto.Result;
import com.qilu.service.IAppointmentSlotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/appointment-slot")
public class AppointmentSlotController {

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @GetMapping("/of/service-point/{id}")
    public Result queryByServicePointId(@PathVariable("id") Long servicePointId) {
        return appointmentSlotService.queryByServicePointId(servicePointId);
    }
}
