package com.hmdp.voucher.config;

import com.hmdp.voucher.service.IVoucherService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class VoucherWarmUpRunner implements CommandLineRunner {

    @Resource
    private IVoucherService voucherService;

    @Override
    public void run(String... args) throws Exception {
        log.info("检测到系统启动，开始自动预热秒杀券库存...");
        try {
            voucherService.warmUpAllSeckillVoucher();
            log.info("秒杀券库存自动预热完成！");
        } catch (Exception e) {
            log.error("秒杀券库存预热失败！错误原因：", e);
        }
    }
}
