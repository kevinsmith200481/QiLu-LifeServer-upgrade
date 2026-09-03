package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.entity.ServicePoint;

public interface IServicePointService extends IService<ServicePoint> {

    Result queryById(Long id);

    Result saveServicePoint(ServicePoint servicePoint);

    Result updateServicePoint(ServicePoint servicePoint);

    Result queryByCategory(Integer categoryId, Integer current, Double x, Double y);

    Result queryByName(String name, Integer current);

    Result queryEnabledPoints();

    Result queryAdminPage(Integer current, Integer status, String name);

    Result approveServicePoint(Long id);

    Result enableServicePoint(Long id);

    Result disableServicePoint(Long id);

    Result deleteServicePoint(Long id);
}
