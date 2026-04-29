package com.hmdp.gateway;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.reactor.context.SaReactorHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

/**
 * 网关服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
@Slf4j
public class GatewayApplication {

    public static void main(String[] args) {
        log.info("GatewayApplication starting...");
        SpringApplication.run(GatewayApplication.class, args);
    }


    @Bean
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                // 1. 拦截所有路径
                .addInclude("/**")
                // 2. 放行不需要登录的路径（一定要和后端拦截器的排除路径对齐）
                .addExclude("/user/login", "/user/code", "/actuator/**")
                // 3. 核心鉴权逻辑：检查是否登录
                .setAuth(obj -> {
                    // 登录校验：只要不在上面的 exclude 列表里，必须登录才能通过
                    SaRouter.match("/**", r -> StpUtil.checkLogin());
                })
                // 4. 异常处理
                .setError(e -> {
                    return Result.fail("网关拦截：" + e.getMessage());
                });
    }

}

