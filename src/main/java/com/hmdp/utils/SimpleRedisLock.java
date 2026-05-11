package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private final StringRedisTemplate stringRedisTemplate;
    private String name; // 锁的key
    private static final String KEY_PREFIX = "lock:";

    // 构造方法：传入redis模板 + 业务名称（比如 order）
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name=name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        long threadId = Thread.currentThread().getId();
        boolean success=stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}