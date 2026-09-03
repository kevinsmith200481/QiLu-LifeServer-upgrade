package com.qilu.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.Result;
import com.qilu.entity.ServiceCategory;
import com.qilu.mapper.ServiceCategoryMapper;
import com.qilu.service.IServiceCategoryService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.qilu.utils.RedisConstants.CACHE_SERVICE_CATEGORY_LIST_KEY;

@Service
public class ServiceCategoryServiceImpl extends ServiceImpl<ServiceCategoryMapper, ServiceCategory> implements IServiceCategoryService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryCategoryList() {
        String list = stringRedisTemplate.opsForValue().get(CACHE_SERVICE_CATEGORY_LIST_KEY);
        if (list != null) {
            return Result.ok(JSONUtil.toList(list, ServiceCategory.class));
        }
        List<ServiceCategory> categories = query().eq("status", 1).orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(CACHE_SERVICE_CATEGORY_LIST_KEY, JSONUtil.toJsonStr(categories));
        return Result.ok(categories);
    }
}
