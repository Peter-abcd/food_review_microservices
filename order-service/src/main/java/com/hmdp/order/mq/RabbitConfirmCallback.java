package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * 生产者确认回调（Publisher Confirm / Return）
 *
 * 背景：
 *   application.yaml 里已经开了 publisher-confirm-type=correlated 和 publisher-returns=true，
 *   但如果没有注册 ConfirmCallback / ReturnsCallback，broker 返回的 ack/nack 会被静默丢弃，
 *   "消息发出去"和"broker 收到了"就分不清了。
 *
 * 两类回调的区别（这是最容易搞错的地方）：
 *   confirm —— broker 是否收到了消息。
 *              ack=true  收到了
 *              ack=false 没收到（队列不存在、磁盘告警、broker 内部错误）
 *   returns —— broker 收到了，但【路由不到任何队列】（需要 mandatory=true，本项目已开）。
 *              典型场景：routingKey 写错。
 *              ⚠ 这种情况下 confirm 依然是 ack=true！只看 confirm 会漏判，
 *                所以路由失败必须靠 returns 回调发现，并在这里做补偿。
 *
 * 单条消息维度的确认见 SeckillOrderProducer#attachConfirmCallback（能拿到业务对象），
 * 这里作为兜底 + 统一日志。
 */
@Component
@Slf4j
public class RabbitConfirmCallback implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private MessageConverter messageConverter;

    @Resource
    private SeckillRedisCompensator compensator;

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
        log.info("RabbitMQ 生产者确认回调已注册（ConfirmCallback + ReturnsCallback）");
    }

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        String id = correlationData == null ? "unknown" : correlationData.getId();
        if (ack) {
            log.debug("broker 已确认消息 id={}", id);
        } else {
            log.error("broker 【未确认】消息 id={}, cause={}（消息可能已丢失，需依赖补偿逻辑）", id, cause);
        }
    }

    /**
     * 路由失败回调 —— 这里是"消息没进任何队列"的兜底补偿。
     *
     * 为什么在这里从消息体反解业务字段：
     *   全局回调拿不到原始业务对象，但用 Jackson2JsonMessageConverter 序列化后，
     *   消息体就是 SeckillOrderMessage 的 JSON，可以直接反向转换回来。
     */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        log.error("消息【路由失败】未进入任何队列: exchange={}, routingKey={}, replyCode={}, replyText={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText());

        try {
            Object payload = messageConverter.fromMessage(returned.getMessage());
            if (payload instanceof SeckillOrderMessage msg) {
                log.error("路由失败的秒杀订单消息，触发 Redis 预扣补偿 orderId={}", msg.getOrderId());
                compensator.compensate(msg.getOrderId(), msg.getUserId(), msg.getVoucherId(),
                        "路由失败: " + returned.getReplyText());
            } else {
                log.warn("路由失败的消息不是秒杀订单消息，跳过补偿: payloadType={}",
                        payload == null ? "null" : payload.getClass().getName());
            }
        } catch (Exception e) {
            log.error("路由失败消息反解失败，无法自动补偿，需人工介入: {}", e.getMessage(), e);
        }
    }
}
