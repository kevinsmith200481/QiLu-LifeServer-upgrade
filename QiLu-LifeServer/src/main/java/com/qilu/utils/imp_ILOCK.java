package com.qilu.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class imp_ILOCK implements ILOCK{
    //名字
    private final String name;
    //锁的前缀
    public static final String KEY_TRY_LOCK="lock:";
    //锁的id
    private final String UUID_KEY=UUID.randomUUID().toString(true)+"-";
    //提前调用lua脚本
    private static final DefaultRedisScript<Long> redisScript;
    static {
        redisScript=new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("unLock.lua"));
        redisScript.setResultType(Long.class);
    }

    //构造函数穿参
    public imp_ILOCK(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private final StringRedisTemplate stringRedisTemplate;
    //获取锁方法
    @Override
    public boolean tryGetLock(Long timeout) {
        //锁的唯一标识
        String threadName = UUID_KEY+Thread.currentThread().getName();
        //获取锁
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY_TRY_LOCK + name, threadName, timeout, TimeUnit.SECONDS);
        //返回正确信息
        return Boolean.TRUE.equals(b);
    }

    @Override
    public void releaseLock() {
        //调用lua脚本，实现释放锁的中判断和释放的原子性
        stringRedisTemplate.execute(redisScript,
                Collections.singletonList(KEY_TRY_LOCK+name),
                UUID_KEY+Thread.currentThread().getName());

//        String threadName = UUID_KEY+Thread.currentThread().getName();
//        String Id = stringRedisTemplate.opsForValue().get(KEY_TRY_LOCK + name);
//        if (threadName.equals(Id)){
//            stringRedisTemplate.delete(KEY_TRY_LOCK + name);
//        }

    }

}
