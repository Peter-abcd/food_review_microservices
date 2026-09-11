package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（注册登录拦截器）。
 *
 * 【@ConditionalOnWebApplication(SERVLET) 是必须的，不是可有可无】
 *   本类 implements WebMvcConfigurer —— 那是 spring-webmvc 的接口。
 *   而 gateway-service 是响应式的，pom 里主动排除了 spring-boot-starter-web，
 *   classpath 上根本没有 WebMvcConfigurer。
 *   本类又在 common 的 AutoConfiguration.imports 里 → 网关启动解析导入配置时会去读
 *   本类的接口，读不到就抛：
 *     BeanDefinitionStoreException: Failed to process import candidates for
 *       configuration class [com.hmdp.gateway.GatewayApplication]
 *     Caused by: FileNotFoundException: class path resource [org/springframework/
 *       web/servlet/config/annotation/WebMvcConfigurer.class] cannot be opened
 *   → 网关直接起不来。加了这个条件，非 Servlet 环境会跳过本类。
 *   （踩过：单机起服务时网关一直启动失败，排查方向容易先怀疑网关自己的配置）
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 刷新Token拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // 2. 登录确认拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/actuator/**",
                        // 【必须排除 /error】否则"没有匹配的 handler"会被转发到 /error，
                        // 而 /error 不在排除名单里就会被本拦截器拦下，最终把 404 变成 401。
                        // 后果：任何 Feign 路径写错/接口不存在都会被误读成鉴权失败，
                        // 排查时方向直接跑偏（本项目真的踩过）。
                        "/error"
                ).order(1);
    }
}