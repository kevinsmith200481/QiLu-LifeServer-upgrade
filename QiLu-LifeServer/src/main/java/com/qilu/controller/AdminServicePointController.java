package com.qilu.controller;

import com.qilu.annotation.Log;
import com.qilu.dto.Result;
import com.qilu.entity.ServicePoint;
import com.qilu.service.IServicePointService;
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
@RequestMapping("/admin/service-point")
public class AdminServicePointController {

    @Resource
    private IServicePointService servicePointService;

    @GetMapping("/page")
    public Result queryServicePointPage(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "name", required = false) String name) {
        return servicePointService.queryAdminPage(current, status, name);
    }

    @PostMapping
    @Log(module = "ServicePoint", operation = "Create service point")
    public Result saveServicePoint(@RequestBody ServicePoint servicePoint) {
        return servicePointService.saveServicePoint(servicePoint);
    }

    @PutMapping
    @Log(module = "ServicePoint", operation = "Update service point")
    public Result updateServicePoint(@RequestBody ServicePoint servicePoint) {
        return servicePointService.updateServicePoint(servicePoint);
    }

    @PutMapping("/{id}/approve")
    @Log(module = "ServicePoint", operation = "Approve service point")
    public Result approveServicePoint(@PathVariable("id") Long id) {
        return servicePointService.approveServicePoint(id);
    }

    @PutMapping("/{id}/enable")
    @Log(module = "ServicePoint", operation = "Enable service point")
    public Result enableServicePoint(@PathVariable("id") Long id) {
        return servicePointService.enableServicePoint(id);
    }

    @PutMapping("/{id}/disable")
    @Log(module = "ServicePoint", operation = "Disable service point")
    public Result disableServicePoint(@PathVariable("id") Long id) {
        return servicePointService.disableServicePoint(id);
    }

    @DeleteMapping("/{id}")
    @Log(module = "ServicePoint", operation = "Delete service point")
    public Result deleteServicePoint(@PathVariable("id") Long id) {
        return servicePointService.deleteServicePoint(id);
    }
}
