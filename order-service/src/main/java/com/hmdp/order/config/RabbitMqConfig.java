package com.hmdp.order.config;

import com.hmdp.utils.MqConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置
 *
 * 队列拓扑（秒杀订单）：
 *
 *   producer ──► seckill.order.exchange ──► seckill.order.queue
 *                                                  │ 消费失败 nack(requeue=false)
 *                                                  ▼
 *                                          seckill.order.retry.exchange
 *                                                  │
 *                                                  ▼
 *                                       seckill.order.retry.queue
 *                                       （x-message-ttl = 10s）
 *                                                  │ TTL 到期，死信回主交换机
 *                                                  └──────► seckill.order.exchange（回到主队列）
 *
 *   超过最大重试次数后，消费者主动投递到 seckill.order.dlx.exchange 并 ack 原消息。
 *
 * 为什么用 retry 队列而不是 basicNack(requeue=true)：
 *   requeue=true 是"立刻、无限"重投，没有退避也没有上限，业务异常时会把 CPU 和日志打满；
 *   走 retry 队列才有"间隔"和"可计数"的重试。
 */
@Configuration
public class RabbitMqConfig {

    /** 重试延迟：消息进入重试队列后等待这么久再回到主队列（毫秒） */
    public static final int SECKILL_RETRY_TTL_MS = 10_000;

    /**
     * 消息转换器：改用 JSON，而不是 Spring AMQP 默认的 Java 序列化。
     *
     * 必须显式配置的原因：
     *   Spring AMQP 3.x 为修复 CVE-2023-34050，给 Java 反序列化加了"类名白名单"，
     *   默认只放行 java.lang / java.util 等基础类型。业务 DTO（com.hmdp.dto.SeckillOrderMessage）
     *   不在白名单内，消费者端会抛：
     *     SecurityException: Attempt to deserialize unauthorized class com.hmdp.dto.SeckillOrderMessage
     *   而且这个异常发生在 listener 方法被调用之前，手动 ACK 模式下没有任何 ack/nack 执行，
     *   消息会永久卡在 unacked 状态（既不被重投也不进死信）。
     *
     *   JSON 转换器没有白名单限制（不涉及对象反序列化），并且消息在 RabbitMQ 控制台里可读。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 秒杀订单交换机（Direct）
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(MqConstants.SECKILL_ORDER_EXCHANGE);
    }

    /**
     * 秒杀订单死信交换机（Direct）
     */
    @Bean
    public DirectExchange seckillOrderDlxExchange() {
        return new DirectExchange(MqConstants.SECKILL_ORDER_DLX_EXCHANGE);
    }

    /**
     * 秒杀订单重试交换机（Direct）
     */
    @Bean
    public DirectExchange seckillOrderRetryExchange() {
        return new DirectExchange(MqConstants.SECKILL_ORDER_RETRY_EXCHANGE);
    }

    /**
     * 秒杀订单主队列。
     *
     * 注意：DLX 指向的是【重试交换机】而不是最终的【死信交换机】。
     * 这样消费者 nack(requeue=false) 之后消息会先进重试队列等待，
     * 而不是直接进死信、也不是立刻回主队列。
     */
    @Bean
    public Queue seckillOrderQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", MqConstants.SECKILL_ORDER_RETRY_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstants.SECKILL_ORDER_RETRY_ROUTING_KEY);
        return QueueBuilder.durable(MqConstants.SECKILL_ORDER_QUEUE)
                .withArguments(args)
                .build();
    }

    /**
     * 秒杀订单重试队列。
     *
     * x-message-ttl          消息在队列里存活多久（即"延迟"多久）
     * x-dead-letter-exchange TTL 到期后投递到哪个交换机 —— 主交换机，实现回环
     */
    @Bean
    public Queue seckillOrderRetryQueue() {
        return QueueBuilder.durable(MqConstants.SECKILL_ORDER_RETRY_QUEUE)
                .withArgument("x-message-ttl", SECKILL_RETRY_TTL_MS)
                .withArgument("x-dead-letter-exchange", MqConstants.SECKILL_ORDER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.SECKILL_ORDER_ROUTING_KEY)
                .build();
    }

    /**
     * 秒杀订单死信队列
     */
    @Bean
    public Queue seckillOrderDlxQueue() {
        return QueueBuilder.durable(MqConstants.SECKILL_ORDER_DLX_QUEUE).build();
    }

    /**
     * 绑定：秒杀订单队列 -> 秒杀订单交换机
     */
    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillOrderExchange) {
        return BindingBuilder.bind(seckillOrderQueue)
                .to(seckillOrderExchange)
                .with(MqConstants.SECKILL_ORDER_ROUTING_KEY);
    }

    /**
     * 绑定：重试队列 -> 重试交换机
     */
    @Bean
    public Binding seckillOrderRetryBinding(Queue seckillOrderRetryQueue,
                                            DirectExchange seckillOrderRetryExchange) {
        return BindingBuilder.bind(seckillOrderRetryQueue)
                .to(seckillOrderRetryExchange)
                .with(MqConstants.SECKILL_ORDER_RETRY_ROUTING_KEY);
    }

    /**
     * 绑定：死信队列 -> 死信交换机
     */
    @Bean
    public Binding seckillOrderDlxBinding(Queue seckillOrderDlxQueue, DirectExchange seckillOrderDlxExchange) {
        return BindingBuilder.bind(seckillOrderDlxQueue)
                .to(seckillOrderDlxExchange)
                .with(MqConstants.SECKILL_ORDER_DLX_ROUTING_KEY);
    }
}
