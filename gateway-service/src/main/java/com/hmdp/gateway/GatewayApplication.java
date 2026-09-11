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
                // 2. 放行不需要登录的路径
                //    【必须和后端 LoginInterceptor 的 excludePathPatterns 保持一致】
                //    两边不一致会出现"经网关看不了店铺列表、直连服务却能看"这种诡异现象 ——
                //    之前网关只放行 /user/login、/user/code，而下游把 /shop/**、/voucher/**、
                //    /shop-type/** 都当公开接口，游客请求在网关这层就被拦了。
                .addExclude("/user/login", "/user/code",
                        "/shop/**", "/shop-type/**", "/voucher/**", "/actuator/**")
                // 3. 核心鉴权逻辑：检查是否登录
                .setAuth(obj -> {
                    // 【必须放行 OPTIONS 预检】浏览器跨域时会先发一个不带 token 的预检请求；
                    // 预检被拦下，浏览器就拿不到 Access-Control-Allow-Origin，整个跨域直接不可用
                    // （实测：预检返回的是鉴权失败的 body，没有任何 CORS 响应头）。
                    if ("OPTIONS".equalsIgnoreCase(SaHolder.getRequest().getMethod())) {
                        return;
                    }
                    // 登录校验：只要不在上面的 exclude 列表里，必须登录才能通过
                    SaRouter.match("/**", r -> StpUtil.checkLogin());
                })
                // 4. 异常处理
                //    【鉴权失败要返回 401 + JSON】原来直接 return Result.fail(...)：
                //    Sa-Token 把 POJO 当字符串写回，结果是 HTTP 200 + Content-Type: text/plain
                //    + Java 的 toString()（Result(success=false, errorMsg=...），
                //    前端既拿不到 401 状态码，也解析不了这个 body。
                .setError(e -> {
                    SaHolder.getResponse().setStatus(401);
                    SaHolder.getResponse().setHeader("Content-Type", "application/json;charset=UTF-8");
                    return JSONUtil.toJsonStr(Result.fail("网关拦截：" + e.getMessage()));
                });
    }

}

