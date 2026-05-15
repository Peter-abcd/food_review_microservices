package com.hmdp.shop.config;

import com.hmdp.shop.service.IShopService;
import jakarta.annotation.Resource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ShopCacheWarmupRunner implements ApplicationRunner {

    @Resource
    private IShopService shopService;


    @Override
    public void run(ApplicationArguments args) throws Exception {
        shopService.warmUpCache();
    }
}
