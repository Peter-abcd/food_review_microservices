package com.hmdp.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 秒杀相关可动态调整的参数 —— 放在 Nacos 里，改完不需要重启服务。
 *
 * 为什么单独抽一个 bean，而不是直接给 SeckillOrderConsumer 加 @RefreshScope：
 *   消费者身上挂着 @RabbitListener，给监听器 bean 本身加刷新作用域会导致容器重建，
 *   可能丢正在处理的消息。把「会变的配置」隔离到这一个小 bean，
 *   刷新时只重建它，监听容器纹丝不动 —— 消费者用到的时候现取。
 *
 * 配置来源：Nacos（dataId=order-service.yaml）；Nacos 上没有就用这里的默认值。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "hmdp.seckill")
public class SeckillProperties {

    /** 单条秒杀消息最多尝试次数（含首次）。调大更宽容，调小更快进死信 */
    private int retryMaxAttempts = 3;

    /** 重试计数在 Redis 里的存活时间（分钟） */
    private long retryCountTtlMinutes = 30L;
}
