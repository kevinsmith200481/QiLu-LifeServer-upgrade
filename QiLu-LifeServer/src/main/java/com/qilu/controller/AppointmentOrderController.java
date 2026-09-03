package com.qilu.controller;

import com.qilu.dto.Result;
import com.qilu.service.IAppointmentOrderService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/appointment-order")
public class AppointmentOrderController {

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @PostMapping("/reserve/{slotId}")
    public Result reserveSlot(@PathVariable("slotId") Long slotId) {
        return appointmentOrderService.reserveSlot(slotId);
    }

    @GetMapping("/mine")
    public Result queryMyOrders() {
        return appointmentOrderService.queryMyOrders();
    }

    @GetMapping("/{orderId}")
    public Result queryMyOrderDetail(@PathVariable("orderId") Long orderId) {
        return appointmentOrderService.queryMyOrderDetail(orderId);
    }

    @PutMapping("/cancel/{orderId}")
    public Result cancelOrder(@PathVariable("orderId") Long orderId) {
        return appointmentOrderService.cancelOrder(orderId);
    }

    @DeleteMapping("/{orderId}")
    public Result deleteOrder(@PathVariable("orderId") Long orderId) {
        return appointmentOrderService.deleteOrder(orderId);
    }
}
