package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
         Shop shop=cacheClient.queryWithPassThrough("cache:shop:" ,id, Shop.class,this::getById, 20L, TimeUnit.SECONDS);
        // 互斥锁解决缓存击穿
        // Shop shop=queryWithMutex( id);
        // 逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 7.返回
        return Result.ok(shop);
    }

    /**
     * 互斥锁解决缓存击穿
     */
    public Shop queryWithMutex(Long id) {
        String key = "cache:shop:" + id;
        // 1.从Redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中是否是空值（解决缓存穿透）
        if (shopJson != null) {
            // 返回错误信息
            return null;
        }

        // 4.实现缓存重建
        Shop shop = null;
        try {
            // 4.1 获取互斥锁
            boolean islock = tryLock("lock:shop:" + id);
            // 4.2 判断是否获取锁成功
            if (!islock) {
                // 获取锁失败，休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 4.4 成功，查询数据库
            shop = this.getById(id);
            // 模拟延迟
            Thread.sleep(200);
            // 5.不存在，返回
            if (shop == null) {
                // 将空值写入Redis
                stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
                return null;
            }
            // 6.存在，写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unLock("lock:shop:" + id);
        }

        return shop;
    }

    /**
     * 逻辑过期解决缓存击穿（修复完成）
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = "cache:shop:" + id;
        // 1.查询Redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.不存在，直接返回null
            return null;
        }
        // 4.命中，反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回
            return shop;
        }

        // 5.2 已过期，需要缓存重建
        // 6.获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 6.3 成功，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }

        // 6.4 返回过期的旧数据
        return shop;
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存穿透解决方案
     */
    public Shop queryWithPassThrough(Long id) {
        String key = "cache:shop:" + id;
        // 1.从Redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否为空值（解决缓存穿透）
        if (shopJson != null) {
            return null;
        }
        // 3.不存在，查询数据库
        Shop shop = getById(id);
        // 4.数据库不存在
        if (shop == null) {
            // 空值写入Redis
            stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
            return null;
        }
        // 5.存在，写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 获取锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return flag != null && flag;
    }

    /**
     * 释放锁
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 店铺数据写入Redis（带逻辑过期）
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = this.getById(id);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除Redis缓存
        stringRedisTemplate.delete("cache:shop:" + id);
        return Result.ok();
    }
}