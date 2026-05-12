package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
   /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询秒杀券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        // 5.进入创建订单逻辑（加锁 + 事务）// 锁：使用 userId intern() 保证同一个用户用同一个锁对象
        Long userId = UserHolder.getUser().getId();
        //创建对象
    //  SimpleRedisLock lock =  new SimpleRedisLock("order:" + userId,stringRedisTemplate);
    RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
      boolean islock =  lock.tryLock();
      //判断锁是否获取成功
        if (!islock) {
            return Result.fail("请勿重复下单");
        }
        try {
            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy() ;
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            lock.unlock();
        }


    }*/
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
@Override
        public void run() {
            while ( true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("处理订单异常");
                }
            }
        }
    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //创建对象
        Long userId = voucherOrder.getUserId();
        //  SimpleRedisLock lock =  new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean islock =  lock.tryLock();
        //判断锁是否获取成功
        if (!islock) {
            log.error("请勿重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
       Long result =     stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString());
        //2.判断结果是否为零
        int r=result.intValue();
        if (r!=0){
            // 2.1 不为零，返回错误信息
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为零，创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderid=redisIdWorker.nextId("order");
        voucherOrder.setId(orderid);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        Long orderId = redisIdWorker.nextId("order");
        orderTasks.add(voucherOrder);
        //获取代理对象
        IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy() ;
        //3.返回订单id
        return Result.ok(orderId);
    }
    /**
     * 秒杀创建订单（核心逻辑：加锁 + 事务保证原子性）
     */
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        Long userId = voucherOrder.getUserId();

            // 5.1 一人一单：查询该用户是否已经买过
            int count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .count();
            if (count > 0) {
                log.error("请勿重复下单");
                return;
            }

            // 6. 扣减库存【修复BUG】
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId())  // 条件1：券ID匹配
                    .gt("stock", 0)              // 条件2：库存>0才扣减（防止超卖）
                    .update();

            if (!success) {
                log.error("库存不足");
                return ;
            }

            // 7. 创建订单

            save(voucherOrder);

            // 8. 返回订单ID

    }
}