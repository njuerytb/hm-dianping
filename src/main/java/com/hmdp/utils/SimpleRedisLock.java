package com.hmdp.utils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private final StringRedisTemplate stringRedisTemplate;
    private String name; // 锁的key
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    // 构造方法：传入redis模板 + 业务名称（比如 order）
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name=name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId =ID_PREFIX+ Thread.currentThread().getId();
        boolean success=stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unLock(){
    stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
   /* @Override
    public void unLock() {
        //获取锁中的线程ID
        String threadId =ID_PREFIX+ Thread.currentThread().getId();
        //获取锁中的标识
     String id=   stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
     //判断线程ID是否一致
        if (threadId.equals(id)){
            stringRedisTemplate.opsForValue().getOperations().delete(KEY_PREFIX + name);
        }
    }*/
}