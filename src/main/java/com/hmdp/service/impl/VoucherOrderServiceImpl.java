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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 优惠券订单服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;

    // Redis 全局唯一 ID 生成器（时间戳 + 序列号），解决数据库自增 ID 的瓶颈与安全问题
    private final RedisIdWorker redisIdWorker;

    /**
     * 秒杀下单：校验时间 -> 校验库存 -> 扣减库存 -> 创建订单
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查出优惠券信息（含当前库存），用 beginTime/endTime/stock 做校验
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
        // 库存校验
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
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
        long orderID = redisIdWorker.nextID("order");
        voucherOrder.setId(orderID);
        // 从 ThreadLocal 获取当前登录用户（由拦截器存入）
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 落库订单
        save(voucherOrder);
        return Result.ok(orderID);
    }
}
