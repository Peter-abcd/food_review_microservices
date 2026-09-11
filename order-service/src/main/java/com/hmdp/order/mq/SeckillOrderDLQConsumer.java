package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.utils.MqConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;

@Component
@Slf4j
public class SeckillOrderDLQConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = MqConstants.SECKILL_ORDER_DLX_QUEUE)
    public void onMessage(SeckillOrderMessage message, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.error("死信队列收到消息，订单处理失败需要人工干预: orderId={}, userId={}, voucherId={}, retryCount={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId(), message.getRetryCount());

        try {
            String pendingOrderKey = "seckill:order:pending";
            String orderInfo = String.format("%d:%d:%d:%d",
                    message.getOrderId(),
                    message.getUserId(),
                    message.getVoucherId(),
                    System.currentTimeMillis());
            stringRedisTemplate.opsForList().rightPush(pendingOrderKey, orderInfo);
            log.info("失败订单已记录到待处理列表: orderId={}", message.getOrderId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("记录失败订单异常: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
