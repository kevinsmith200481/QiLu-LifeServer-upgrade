package com.qilu.utils;

import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class CreateOnlyId {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final Long BEGIN_TIME_CREATE_ID = 1735689600L;
    public long createId(String keyToCreateId) {
        //获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long nowSecond = epochSecond - BEGIN_TIME_CREATE_ID;
        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyToCreateId + ":" + date);

        // 3.拼接并返回
        return nowSecond << 32 | count;
    }
}
