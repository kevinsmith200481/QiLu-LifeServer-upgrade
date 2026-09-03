package com.qilu.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class Cache_tools {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // Empty-value cache protects legacy and campus detail queries from cache penetration.
    public <R,ID>  R queryWithPassThoughtTools(String key, ID id, Class<R> clazz,
                                         Function<ID,R> function,Long time, TimeUnit timeUnit) {
        String key1 = key + id;
        //1.从redis查询商铺缓存
        String value = stringRedisTemplate.opsForValue().get(key1);
        //2.判断是否存在
        if (StrUtil.isNotBlank(value)) {
            //存在直接返回
            return JSONUtil.toBean(value, clazz);
        }
        //判断命中的是否为空值
        if (value != null) {
            return null;
        }
        //3.不存在根据id查询数据库
        R apply = function.apply(id);
        if (apply == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key1,"",2L, TimeUnit.MINUTES);
            return null;
        }
        //4.写入redis
        stringRedisTemplate.opsForValue().set(key1,JSONUtil.toJsonStr(apply),time, timeUnit);
        return apply;
    }
}
