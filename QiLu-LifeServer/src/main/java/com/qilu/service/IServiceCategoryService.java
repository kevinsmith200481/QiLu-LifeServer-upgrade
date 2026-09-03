package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.entity.ServiceCategory;

public interface IServiceCategoryService extends IService<ServiceCategory> {

    Result queryCategoryList();
}
