package com.hmdp.voucher.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.utils.RedisConstants;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.hmdp.voucher.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //保存秒杀库存到redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(),voucher.getStock().toString());

    }

    @Override
    public Result deductStock(Long voucherId) {
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足");
        }
        // 更新Redis中的库存
        stringRedisTemplate.opsForValue().decrement(SECKILL_STOCK_KEY + voucherId);
        return Result.ok();
    }

    @Override
    public void warmUpAllSeckillVoucher() {
        // 1. 查询所有正在进行中或即将开始的秒杀券信息
        // 注意：这里需要注入 SeckillVoucherMapper 或者直接查 tb_seckill_voucher 表
        List<SeckillVoucher> list = seckillVoucherService.list();

        if (list == null || list.isEmpty()) {
            return;
        }

        // 2. 遍历并加载到 Redis
        for (SeckillVoucher seckillVoucher : list) {
            String key = RedisConstants.SECKILL_STOCK_KEY + seckillVoucher.getVoucherId();
            // 如果 Redis 中已经存在了，这段逻辑也可以起到刷新的作用
            stringRedisTemplate.opsForValue().set(key, seckillVoucher.getStock().toString());

//            // 顺便可以把秒杀券的详细信息/状态等也缓存起来（如果需要的话）
//            log.info("预热秒杀券库存成功：voucherId={}, stock={}", seckillVoucher.getVoucherId(), seckillVoucher.getStock());
        }
    }
}