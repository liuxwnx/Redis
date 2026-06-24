package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>
 *  使用 ReentrantLock 悲观锁实现秒杀优惠券
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderLockServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /**
     * 静态可重入锁，确保所有线程共享同一把锁
     */
    private static final Lock lock = new ReentrantLock();

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 秒杀优惠券 - 使用 ReentrantLock 悲观锁保证线程安全
     * @param voucherId 优惠券id
     * @return 秒杀结果
     */
    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        // ReentrantLock 悲观锁：尝试获取锁
        lock.lock();
        try {
            // 1.查询优惠券
            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            // 2.判断秒杀是否开始
            if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
                // 尚未开始
                return Result.fail("秒杀尚未开始!");
            }
            // 3.判断秒杀是否结束
            if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
                // 已结束
                return Result.fail("秒杀已结束!");
            }
            // 4.判断库存是否充足
            if (voucher.getStock() < 1) {
                return Result.fail("库存不足!");
            }
            // 5.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1") // set stock = stock-1
                    .eq("voucher_id", voucherId)// where id = ?
                    .gt("stock", 0)// where stock > 0  乐观锁解决优惠券多买的问题
                    .update();

            if (!success) {
                // 扣减失败
                return Result.fail("库存不足!");
            }
            // 6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 6.1 订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 6.2 用户id
            Long userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);
            // 6.3 优惠券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            // 7.返回订单id
            return Result.ok(orderId);
        } finally {
            // 必须在 finally 中释放锁，确保即使发生异常也能释放
            lock.unlock();
        }
    }
}
