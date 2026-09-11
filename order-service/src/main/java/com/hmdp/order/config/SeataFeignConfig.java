package com.hmdp.order.config;

import feign.RequestInterceptor;
import io.seata.core.context.RootContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 把 Seata 全局事务 ID（XID）透传给下游服务。
 *
 * 为什么必须手写这一个 Bean：
 *   `io.seata:seata-spring-boot-starter` 里【没有】Feign 集成 —— 打开 jar 只看到
 *   io/seata/spring/boot/autoconfigure/** ，没有任何 *Feign* 类。
 *   历史上这一步由 Spring Cloud Alibaba 的 `spring-cloud-starter-alibaba-seata`
 *   （SeataFeignClient）负责，而本项目只引了 seata 自己的 starter。
 *
 *   后果（实测过，不是推测）：调用方的 XID 不往下传 → 下游服务虽然 DataSource 已被
 *   AT 代理，但上下文里没有 XID，**不会注册分支、不写 undo_log**，它的扣减跑在自己的
 *   本地事务里。于是上游回滚时那一刀留痕 —— 这正是"订单没建、库存却扣了"的来源，
 *   也是"跨服务 Seata 保障一致性"这句话能不能成立的关键。
 *
 * 下游不需要额外改动：seata-spring-boot-starter 的 SeataHttpAutoConfiguration 会注册
 * io.seata.integration.http.JakartaTransactionPropagationInterceptor，
 * 它按 RootContext.KEY_XID 这个头名把 XID 还原回上下文。
 */
@Configuration
public class SeataFeignConfig {

    @Bean
    public RequestInterceptor seataXidRequestInterceptor() {
        return template -> {
            String xid = RootContext.getXID();
            if (StringUtils.hasText(xid)) {
                template.header(RootContext.KEY_XID, xid);
            }
        };
    }
}
