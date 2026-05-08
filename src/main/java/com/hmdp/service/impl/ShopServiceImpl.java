package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
        @Override
    public Result queryById(Long id) {
            //1.从Redis查询id
            String shopJson=stringRedisTemplate.opsForValue().get("cache:shop:" + id);
            //2.判断是否存在
           if (StrUtil.isNotBlank(shopJson)){
               //3.存在，返回
               Shop shop= JSONUtil.toBean(shopJson,Shop.class);
               return Result.ok(shop);
           }
            //判断命中是否是空值
            if (shopJson != null){
                //返回错误信息
                return Result.fail("店铺不存在");
            }
            //4.不存在，查询数据库
            Shop shop=this.getById(id);
            //5.不存在，返回
            if (shop==null){
                //将空值写入Redis
                stringRedisTemplate.opsForValue().set("cache:shop:" + id, "",2, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            //6.存在，保存Redis
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
            //7.返回
            return Result.ok(shop);
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
            //2.删除Redis
            stringRedisTemplate.delete("cache:shop:" + id);
        return Result.ok();
    }
}
