package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private  StringRedisTemplate stringredisTemplate;
    public CacheClient(StringRedisTemplate stringredisTemplate) {
        this.stringredisTemplate = stringredisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringredisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 1.将数据写入Redis
        stringredisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R,ID> R queryWithPassThrough(String  keyprefix, ID id, Class<R> type, Function<ID,R>dbFallback,Long time, TimeUnit unit) {
        String key = keyprefix + id;
        // 1.从Redis查询缓存
        String shopJson = stringredisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断是否为空值（解决缓存穿透）
        if (shopJson != null) {
            return null;
        }
        // 3.不存在，查询数据库
        R r = dbFallback.apply(id);
        // 4.数据库不存在
        if (r == null) {
            // 空值写入Redis
            stringredisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
            return null;
        }
        // 5.存在，写入Redis
        this.set(key, r, time, unit);
        return r;
    }
}
