package com.qilu.controller;

import com.qilu.dto.Result;
import com.qilu.service.IServiceCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/service-category")
public class ServiceCategoryController {

    @Resource
    private IServiceCategoryService serviceCategoryService;

    @GetMapping("/list")
    public Result queryCategoryList() {
        return serviceCategoryService.queryCategoryList();
    }
}
