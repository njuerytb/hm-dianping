package com.hmdp;

import cn.hutool.json.JSON;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
@Resource
private ShopServiceImpl shopService;
@Resource
private RedisIdWorker redisIdWorker;
private ExecutorService es= Executors.newFixedThreadPool(500);
@Resource
private StringRedisTemplate stringRedisTemplate;
@Test
    void testSaveShop() throws InterruptedException {
    shopService.saveShop2Redis(1L,10L);
}
@Test
    void testIdWorker() throws InterruptedException {
    Runnable task=()->{
        for (int i = 0; i < 1000; i++) {
            long id = redisIdWorker.nextId("order");
            System.out.println("id = " + id);
        }
    };
    es.submit(task);
}
@Test
    void loadshopData(){
    //1.查询店铺信息
    List<Shop> list = shopService.list();
    //2.吧店铺按typeid分组
    Map<Long,List<Shop>> map = list.stream().
            collect(Collectors.groupingBy(Shop::getTypeId));
    //3.分批写入Redis
    for (Map.Entry<Long, List<Shop>> entry : map.entrySet()){
        //3.1获取类型id
        Long typeId = entry.getKey();
        String key = "shop:geo:"+typeId;
        //3.2获取同类型的店铺列表
        List<Shop> value = entry.getValue();
        List<RedisGeoCommands.GeoLocation<String>>locations=new ArrayList<>();

        for (Shop shop : value){
            //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
           locations.add(new RedisGeoCommands.GeoLocation<>(
                    shop.getId().toString(),
                    new Point(shop.getX(),shop.getY())
            ));
        }
        stringRedisTemplate.opsForGeo().add(key,locations);
    }
}
}
