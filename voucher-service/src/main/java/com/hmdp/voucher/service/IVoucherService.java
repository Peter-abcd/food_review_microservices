package com.hmdp.voucher.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

//    void addSeckillVoucher(Voucher voucher);

    @Transactional(rollbackFor = Exception.class)
    void addVoucher(Voucher voucher);

    Result deductStock(Long voucherId);

    void warmUpAllSeckillVoucher();

    Result deductNormalVoucherStock(Long voucherId);
}