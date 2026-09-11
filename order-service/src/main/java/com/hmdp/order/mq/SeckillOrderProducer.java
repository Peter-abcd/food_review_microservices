package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.utils.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 秒杀订单消息生产者
 *
 * 职责：
 *   1. 流量削峰 —— 把瞬时并发请求转成异步消息
 *   2. 解耦     —— 资格校验（Redis+Lua）与订单落库分开
 *   3. 可靠性   —— 通过 broker confirm / return 回调感知投递失败，并补偿 Redis 预扣数据
 */
@Component
@Slf4j
public class SeckillOrderProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private SeckillRedisCompensator compensator;

    /**
     * 发送秒杀订单消息。
     *
     * 【重要】返回值含义：只代表"消息已提交给 RabbitTemplate"，不代表 broker 已确认。
     * 真正的投递结果通过 CorrelationData.getFuture() 异步回调获得（见 attachConfirmCallback）。
     * 异步链路里不存在"同步返回 true 就等价于投递成功"—— 这正是必须靠 confirm 回调的原因。
     *
     * @param message 秒杀订单消息
     * @return true-已提交（不代表投递成功），false-提交阶段就抛异常了（已做补偿）
     */
    public boolean sendSeckillOrderMessage(SeckillOrderMessage message) {
        return doSend(message, "同步");
    }

    /**
     * 异步发送秒杀订单消息（秒杀主流程使用，不阻塞 Tomcat 线程）。
     */
    public boolean sendSeckillOrderMessageAsync(SeckillOrderMessage message) {
        return doSend(message, "异步");
    }

    private boolean doSend(SeckillOrderMessage message, String mode) {
        Long orderId = message.getOrderId();
        try {
            // 关键：correlationId 直接放 orderId，
            // 这样 confirm 回调里仅凭 id 就能定位到具体订单，不需要额外的映射表。
            CorrelationData correlationData = new CorrelationData(String.valueOf(orderId));

            attachConfirmCallback(correlationData, message);

            rabbitTemplate.convertAndSend(
                    MqConstants.SECKILL_ORDER_EXCHANGE,
                    MqConstants.SECKILL_ORDER_ROUTING_KEY,
                    message,
                    correlationData
            );
            log.info("秒杀订单消息已提交({}): orderId={}, userId={}, voucherId={}",
                    mode, orderId, message.getUserId(), message.getVoucherId());
            return true;
        } catch (Exception e) {
            log.error("秒杀订单消息提交失败 orderId={}, error={}", orderId, e.getMessage(), e);
            // 连提交都没成功，broker 不可能收到，直接补偿（幂等）
            compensator.compensate(orderId, message.getUserId(), message.getVoucherId(), "提交阶段异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 注册单条消息的 confirm 回调。
     *
     * 用 getFuture().whenComplete 而不是只靠全局 ConfirmCallback 的原因：
     *   全局回调只能拿到 correlationId（一个字符串），拿不到 userId/voucherId，
     *   而补偿 Redis 预扣需要这两个值。这里闭包直接捕获了 message。
     *
     * 【两个独立维度，缺一不可】
     *   confirm.ack=false  -> broker 根本没收到（队列不存在、磁盘告警、内部错误）
     *   correlationData.getReturned() != null -> broker 收到了，但路由不到任何队列
     *     （routingKey 写错）。这种情况下 confirm 仍然是 ack=true，只看 ack 会漏判。
     */
    private void attachConfirmCallback(CorrelationData correlationData, SeckillOrderMessage message) {
        Long orderId = message.getOrderId();

        correlationData.getFuture().whenComplete((confirm, ex) -> {
            boolean ack = ex == null && confirm != null && confirm.isAck();
            boolean returned = correlationData.getReturned() != null;

            if (ack && !returned) {
                log.debug("broker 已确认秒杀订单消息 orderId={}", orderId);
                return;
            }

            String cause;
            if (ex != null) {
                cause = "confirm 异常: " + ex.getMessage();
            } else if (returned) {
                cause = "消息路由失败(无匹配队列): " + correlationData.getReturned().getReplyText();
            } else {
                cause = "broker 未确认: " + (confirm != null ? confirm.getReason() : "未知原因");
            }

            log.error("秒杀订单消息【投递失败】，触发 Redis 预扣补偿 orderId={}, cause={}", orderId, cause);
            compensator.compensate(orderId, message.getUserId(), message.getVoucherId(), cause);
        });
    }

    /**
     * 发送消息到死信队列。
     *
     * 当消息处理失败且超过最大重试次数时调用。
     */
    public void sendToDeadLetterQueue(SeckillOrderMessage message, String reason) {
        try {
            message.setRetryCount(message.getRetryCount() == null ? 1 : message.getRetryCount() + 1);
            rabbitTemplate.convertAndSend(
                    MqConstants.SECKILL_ORDER_DLX_EXCHANGE,
                    MqConstants.SECKILL_ORDER_DLX_ROUTING_KEY,
                    message
            );
            log.warn("订单消息发送到死信队列: orderId={}, reason={}, retryCount={}",
                    message.getOrderId(), reason, message.getRetryCount());
        } catch (Exception e) {
            log.error("发送到死信队列失败: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
        }
    }
}
