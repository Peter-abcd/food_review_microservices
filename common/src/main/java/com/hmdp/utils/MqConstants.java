package com.hmdp.utils;

public class MqConstants {
    // 商户缓存广播
    public static final String SHOP_CACHE_EXCHANGE = "shop.cache.exchange";

    // 秒杀订单交换机
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";

    // 秒杀订单队列
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    // 秒杀订单路由键
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    // 秒杀订单死信交换机
    public static final String SECKILL_ORDER_DLX_EXCHANGE = "seckill.order.dlx.exchange";

    // 秒杀订单死信队列
    public static final String SECKILL_ORDER_DLX_QUEUE = "seckill.order.dlx.queue";

    // 秒杀订单死信路由键
    public static final String SECKILL_ORDER_DLX_ROUTING_KEY = "seckill.order.dlx";

    // 秒杀订单重试交换机（主队列消费失败后先进这里，延迟一段时间再回主队列）
    public static final String SECKILL_ORDER_RETRY_EXCHANGE = "seckill.order.retry.exchange";

    // 秒杀订单重试队列（带 TTL，到期后死信回主交换机，实现"延迟重试"）
    public static final String SECKILL_ORDER_RETRY_QUEUE = "seckill.order.retry.queue";

    // 秒杀订单重试路由键
    public static final String SECKILL_ORDER_RETRY_ROUTING_KEY = "seckill.order.retry";
}
