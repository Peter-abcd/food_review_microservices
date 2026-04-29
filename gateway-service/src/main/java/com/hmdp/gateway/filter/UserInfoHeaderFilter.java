package com.hmdp.gateway.filter;

import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONUtil;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class UserInfoHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 检查是否登录（利用 Sa-Token）
        if (StpUtil.isLogin()) {
            // 2. 从 Session 中获取用户信息（你在 Login 时存进去的）
            Object user = StpUtil.getSession().get("user");
            if (user != null) {
                String userJson = JSONUtil.toJsonStr(user);

                // 3. 关键：在响应式环境下，必须通过 mutate() 修改请求并传给下游
                ServerHttpRequest request = exchange.getRequest().mutate()
                        .header("user-info", URLUtil.encode(userJson))
                        .build();

                // 使用修改后的 request 继续执行过滤链
                return chain.filter(exchange.mutate().request(request).build());
            }
        }

        // 未登录或者是免登录接口，直接放行
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 设置优先级。SaReactorFilter 的默认优先级通常很高
        // 我们确保这个过滤器在鉴权之后执行即可，这里设为 0
        return 0;
    }
}