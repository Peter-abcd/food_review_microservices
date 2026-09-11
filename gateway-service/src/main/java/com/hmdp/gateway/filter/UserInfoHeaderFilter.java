package com.hmdp.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 把网关已认证的用户身份下发给下游服务。
 *
 * 链路：user-service 登录时 StpUtil.getSession().set("user", userDTO) 把用户存进 Sa-Token 会话
 *      （会话在 Redis 里，所有服务共享）→ 本过滤器按 token 取出 user → URL 编码后放进
 *      请求头 user-info → 下游 common 的 RefreshTokenInterceptor 解码还原并塞进 ThreadLocal。
 *
 * 【为什么不用 StpUtil.isLogin() / StpUtil.getSession()】
 *   这两个方法依赖"请求上下文"来取当前 token，而网关是 WebFlux 的，
 *   在网关自己的 GlobalFilter 里 Sa-Token 拿不到反应式上下文 —— 实测直接抛：
 *     SaTokenContextException: SaTokenContext 上下文尚未初始化
 *     at cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff.getModelBox(...)
 *     at cn.dev33.satoken.stp.StpUtil.isLogin(StpUtil.java:460)
 *     at com.hmdp.gateway.filter.UserInfoHeaderFilter.filter(UserInfoHeaderFilter.java:20)
 *   后果是**所有能通过鉴权的请求（含 /user/login、/user/code 这类放行路径）全部 500**。
 *   改用显式传 token 的 API：StpUtil.getLoginIdByToken(token) 与
 *   StpUtil.getSessionByLoginId(loginId) 都是直接查 DAO(Redis)，不依赖请求上下文。
 */
@Component
public class UserInfoHeaderFilter implements GlobalFilter, Ordered {

    /** 与 sa-token.token-name 保持一致，token 就放在这个请求头里 */
    @Value("${sa-token.token-name:Authorization}")
    private String tokenName;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst(tokenName);

        // 没带 token（含匿名可访问的接口）：直接放行，不放 user-info 头
        if (StrUtil.isBlank(token)) {
            return chain.filter(exchange);
        }

        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            return chain.filter(exchange);
        }

        Object user = StpUtil.getSessionByLoginId(loginId).get("user");
        if (user == null) {
            return chain.filter(exchange);
        }

        // 在响应式环境下必须通过 mutate() 修改请求再传给下游；
        // 用户信息里有中文昵称，放进 Header 前要 URL 编码，否则会踩 Header 非法字符
        String userJson = URLUtil.encode(JSONUtil.toJsonStr(user));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("user-info", userJson)
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        // SaReactorFilter 已经完成鉴权，这里在网关过滤器链里尽早执行
        return 0;
    }
}
