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
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
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
      SimpleRedisLock lock =  new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        //获取锁
      boolean islock =  lock.tryLock(1200);
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
            lock.unLock();
        }


    }
    /**
     * 秒杀创建订单（核心逻辑：加锁 + 事务保证原子性）
     */
    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId) {

        Long userId = UserHolder.getUser().getId();

            // 5.1 一人一单：查询该用户是否已经买过
            int count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();
            if (count > 0) {
                return Result.fail("用户已购买");
            }

            // 6. 扣减库存【修复BUG】
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)  // 条件1：券ID匹配
                    .gt("stock", 0)              // 条件2：库存>0才扣减（防止超卖）
                    .update();

            if (!success) {
                return Result.fail("库存不足");
            }

            // 7. 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 8. 返回订单ID
            return Result.ok(orderId);

    }
}