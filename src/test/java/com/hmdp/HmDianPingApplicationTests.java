package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
@Resource
private ShopServiceImpl shopService;
@Resource
private RedisIdWorker redisIdWorker;
private ExecutorService es= Executors.newFixedThreadPool(500);
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
}
