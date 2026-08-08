package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 优惠券订单服务实现类
 */
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;

    // Redis 全局唯一 ID 生成器（时间戳 + 序列号），解决数据库自增 ID 的瓶颈与安全问题
    private final RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查出优惠券信息（含当前库存），用 beginTime/endTime 做时间校验
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 校验秒杀时间窗口
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 从 ThreadLocal 获取当前登录用户（由拦截器存入）
        Long userId = UserHolder.getUser().getId();
        // 按用户加锁：同一用户串行下单，避免并发重复购买（仅单 JVM 有效）
        synchronized (userId.toString().intern()) {
            // 走代理调用，确保下方 @Transactional 事务生效（绕过 this 自调用）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    /**
     * 创建订单（一人一单 + 扣库存），在事务内执行
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单：同一用户对同一券只能下一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次!");
        }

        // 扣减库存：eq 锁定具体优惠券，gt("stock",0) 保证库存>0才扣减，防止超卖
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1 ")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        // 更新失败（受影响行数为0）说明并发下库存已抢空
        if (!success) {
            return Result.fail("库存不足");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 用 Redis 生成全局唯一订单号，"order" 业务标识区分不同 ID 类型
        long orderID = redisIdWorker.nextID("order");
        voucherOrder.setId(orderID);
        // 关联下单用户
        voucherOrder.setUserId(userId);
        // 关联所购买的优惠券
        voucherOrder.setVoucherId(voucherId);

        // 落库订单（MyBatis-Plus 继承的 save 方法）
        save(voucherOrder);
        return Result.ok(orderID);
    }
}
