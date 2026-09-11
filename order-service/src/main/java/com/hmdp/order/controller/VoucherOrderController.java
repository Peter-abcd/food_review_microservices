package com.hmdp.order.controller;


import com.hmdp.dto.Result;
import com.hmdp.order.config.SeckillProperties;
import com.hmdp.order.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private SeckillProperties seckillProperties;

    @Resource
    private ConfigurableEnvironment environment;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @PostMapping("{id}")
    public Result orderVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.orderVoucher(voucherId);
    }

    /**
     * 诊断接口：当前生效的秒杀参数 + 这些值最终是从哪个配置源读到的。
     *
     * 用途就是验证 Nacos 配置动态下发 —— 在 Nacos 里改完值不用重启服务，
     * 直接刷这个接口就能看到新值，以及它是不是真的来自 Nacos 而不是本地默认值。
     */
    @GetMapping("seckill/config")
    public Result seckillConfig() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("retryMaxAttempts", seckillProperties.getRetryMaxAttempts());
        info.put("retryCountTtlMinutes", seckillProperties.getRetryCountTtlMinutes());
        info.put("source(retry-max-attempts)", sourceOf("hmdp.seckill.retry-max-attempts"));
        info.put("source(retry-count-ttl-minutes)", sourceOf("hmdp.seckill.retry-count-ttl-minutes"));
        return Result.ok(info);
    }

    /**
     * 该键最终由哪个 PropertySource 提供。
     * 返回 Nacos 的 source 名说明配置确实从 Nacos 下发；返回最后那行说明没人配过、用的是代码默认值。
     */
    private String sourceOf(String key) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps.containsProperty(key)) {
                return ps.getName();
            }
        }
        return "none(代码默认值)";
    }

}
