package com.hmdp.order.fallback;

import com.hmdp.dto.Result;
import com.hmdp.order.feign.VoucherFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class VoucherFeignClientFallback implements VoucherFeignClient {

    @Override
    public Result deductStock(Long voucherId) {
        log.warn("voucher-service服务不可用，扣减库存降级处理: voucherId={}", voucherId);
        return Result.fail("库存服务暂时不可用，订单将异步处理");
    }

    @Override
    public Result getVoucherById(Long voucherId) {
        log.warn("voucher-service服务不可用，查询优惠券降级处理: voucherId={}", voucherId);
        return Result.fail("优惠券服务暂时不可用");
    }

    /**
     * 普通券库存扣减降级。
     *
     * 必须返回失败：若降级返回成功，调用方会继续创建订单，
     * 结果就是"库存没扣、订单却建了"，账不平。返回失败让调用方中断并回滚。
     */
    @Override
    public Result deductNormalVoucherStock(Long voucherId) {
        log.warn("voucher-service服务不可用，普通券库存扣减降级处理: voucherId={}", voucherId);
        return Result.fail("库存服务暂时不可用，普通券下单已中止");
    }
}
