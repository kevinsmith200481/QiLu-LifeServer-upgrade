package com.qilu.controller;

import com.qilu.dto.Result;
import com.qilu.entity.ServicePoint;
import com.qilu.service.IServicePointService;
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
@RequestMapping("/service-point")
public class ServicePointController {

    @Resource
    private IServicePointService servicePointService;

    @GetMapping("/{id}")
    public Result queryServicePointById(@PathVariable("id") Long id) {
        return servicePointService.queryById(id);
    }

    @PostMapping
    public Result saveServicePoint(@RequestBody ServicePoint servicePoint) {
        return servicePointService.saveServicePoint(servicePoint);
    }

    @PutMapping
    public Result updateServicePoint(@RequestBody ServicePoint servicePoint) {
        return servicePointService.updateServicePoint(servicePoint);
    }

    @GetMapping("/of/category")
    public Result queryServicePointByCategory(
            @RequestParam("categoryId") Integer categoryId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        return servicePointService.queryByCategory(categoryId, current, x, y);
    }

    @GetMapping("/of/name")
    public Result queryServicePointByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        return servicePointService.queryByName(name, current);
    }

    @GetMapping("/enabled")
    public Result queryEnabledServicePoints() {
        return servicePointService.queryEnabledPoints();
    }
}
