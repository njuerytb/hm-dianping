package com.hmdp.utils;

import cn.hutool.core.collection.CollUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class InitShopGeo {

    public static final String SHOP_GEO_KEY = "shop:geo:";

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void initShopGeo() {
        // 1. 查询所有店铺
        List<Shop> shopList = shopService.list();
        if (CollUtil.isEmpty(shopList)) {
            System.out.println("无店铺数据");
            return;
        }

        // 2. 按类型分组
        Map<Long, List<Shop>> map = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        // 3. 分批写入 GEO
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();
            String key = SHOP_GEO_KEY + typeId;

            for (Shop shop : shops) {
                // 正确写法！解决你报错的问题
                stringRedisTemplate.opsForGeo().add(
                        key,
                        new RedisGeoCommands.GeoLocation<>(
                                shop.getId().toString(),
                                new org.springframework.data.geo.Point(shop.getX(), shop.getY())
                        )
                );
            }
        }

        System.out.println("✅ 店铺 GEO 初始化完成！美食页面恢复正常！");
    }
}