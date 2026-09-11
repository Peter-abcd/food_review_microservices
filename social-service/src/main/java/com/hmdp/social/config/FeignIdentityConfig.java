package com.hmdp.social.config;

import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务间调用透传当前登录用户身份。
 *
 * 【为什么需要它】
 *   下游服务的身份来自请求头 user-info（网关鉴权后注入），common 的 RefreshTokenInterceptor
 *   把它还原进 UserHolder，LoginInterceptor 再据此判断"是否已登录"。
 *   而 LoginInterceptor 的放行名单只有 /user/code、/user/login、/shop/**、/voucher/**、
 *   /shop-type/**、/actuator/**、/error —— **不包含 /user/** 的查询接口**。
 *   所以 social-service 用 Feign 调 user-service 的 /user/{id}、/user/list 时，
 *   请求里没有 user-info 头 → 下游判定为未登录 → 401（实测：curl 直连 /user/list 就是 401）。
 *
 *   对照：order-service 调 voucher-service 之所以通，是因为 /voucher/** 在两边都被放行。
 *
 * 【为什么不直接放开 /user/** 的鉴权】
 *   那会让"按 id 查用户"变成公开接口。正确做法是让**机器间调用携带调用方身份**，
 *   下游照常走鉴权逻辑 —— 和 Seata 的 XID 需要跨服务透传是同一类问题：
 *   上下文不会自己飞过去，得靠拦截器显式传。
 *
 * 链路：浏览器 → 网关(鉴权 + 注入 user-info) → social(RefreshTokenInterceptor 存 UserHolder)
 *      → Feign(本拦截器把 user-info 再带到下游) → user-service(同样还原 UserHolder) → 鉴权通过
 *
 * 注：Feign 调用是同步的，与业务线程同线程，所以能取到 ThreadLocal 里的 UserHolder。
 */
@Configuration
public class FeignIdentityConfig {

    @Bean
    public RequestInterceptor userInfoRequestInterceptor() {
        return template -> {
            UserDTO user = UserHolder.getUser();
            if (user == null) {
                return;   // 无登录上下文（如定时任务、匿名流程）就不带头
            }
            // 与网关写入的编码方式保持一致：URL 编码，避免昵称里的中文/特殊字符踩 Header 非法字符
            String userJson = URLUtil.encode(JSONUtil.toJsonStr(user));
            if (!template.headers().containsKey("user-info")) {
                template.header("user-info", userJson);
            }
        };
    }
}
