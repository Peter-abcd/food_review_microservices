# RocketMQ to RabbitMQ

> package com.hmdp.order.mq;
>
> import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
> import com.hmdp.dto.Result;
> import com.hmdp.dto.SeckillOrderMessage;
> import com.hmdp.entity.VoucherOrder;
> import com.hmdp.order.feign.VoucherFeignClient;
> import com.hmdp.order.mapper.VoucherOrderMapper;
> import com.hmdp.order.metrics.SeckillMetrics;
> import com.hmdp.utils.RedisConstants;
> import lombok.extern.slf4j.Slf4j;
> import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
> import org.apache.rocketmq.spring.core.RocketMQListener;
> import org.redisson.api.RLock;
> import org.redisson.api.RedissonClient;
> import org.springframework.data.redis.core.StringRedisTemplate;
> import org.springframework.stereotype.Component;
>
> import jakarta.annotation.Resource;
> import java.util.concurrent.TimeUnit;
>
> /**
>  * 秒杀订单消息消费者
>  *
>  * 负责消费秒杀资格校验通过后产生的订单消息，完成最终的下单操作。
>  * 核心职责：
>  * 1. 保证消息消费的幂等性（通过订单ID去重）
>  * 2. 执行最终的一致性检查（一人一单、库存扣减）
>  * 3. 处理异常情况并回滚Redis预扣数据
>  * 4. 记录消费指标用于监控
>  *
>  * 采用分布式锁保证同一订单的串行处理，避免重复消费导致的数据不一致问题。
>  */
> @Component
> @RocketMQMessageListener(
>         topic = SeckillOrderProducer.TOPIC_SECKILL_ORDER,
>         consumerGroup = "seckill-order-consumer-group",
>         maxReconsumeTimes = 3
> )
> @Slf4j
> public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {
>
>     @Resource
>     private VoucherOrderMapper voucherOrderMapper;
>
>     @Resource
>     private VoucherFeignClient voucherFeignClient;
>
>     @Resource
>     private StringRedisTemplate stringRedisTemplate;
>
>     @Resource
>     private RedissonClient redissonClient;
>
>     @Resource
>     private SeckillMetrics seckillMetrics;
>
>     private static final int MAX_RETRY_COUNT = 3;
>
>     /**
>      * 处理秒杀订单消息
>      *
>      * 消费流程：
>      * 1. 获取分布式锁，保证同一订单的串行处理
>      * 2. 检查订单是否已存在（幂等性保障）
>      * 3. 二次校验一人一单规则（防止极端情况下的并发问题）
>      * 4. 调用库存服务扣减数据库库存
>      * 5. 创建订单记录到数据库
>      * 6. 清理Redis中的临时订单数据
>      *
>      * 异常处理：
>      * - 任何步骤失败都会回滚Redis预扣数据
>      * - 重试次数超过上限后需要人工干预
>      *
>      * @param message 秒杀订单消息
>      */
>     @Override
>     public void onMessage(SeckillOrderMessage message) {
>         Long orderId = message.getOrderId();
>         Long userId = message.getUserId();
>         Long voucherId = message.getVoucherId();
>
>         log.info("开始处理秒杀订单消息: orderId={}, userId={}, voucherId={}, retryCount={}",
>                 orderId, userId, voucherId, message.getRetryCount());
>
>         String lockKey = "lock:order:" + orderId;
>         RLock lock = redissonClient.getLock(lockKey);
>
>         try {
>             boolean locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
>             if (!locked) {
>                 log.warn("获取订单锁失败，可能正在处理中: orderId={}", orderId);
>                 return;
>             }
>
>             try {
>                 VoucherOrder existingOrder = voucherOrderMapper.selectById(orderId);
>                 if (existingOrder != null) {
>                     log.info("订单已存在，跳过处理: orderId={}", orderId);
>                     seckillMetrics.incrementMqConsumeSuccess();
>                     return;
>                 }
>
>                 Long count = voucherOrderMapper.selectCount(
>                         new LambdaQueryWrapper<VoucherOrder>()
>                                 .eq(VoucherOrder::getUserId, userId)
>                                 .eq(VoucherOrder::getVoucherId, voucherId)
>                 );
>                 if (count > 0) {
>                     log.warn("用户已购买过该优惠券，一人一单校验失败: userId={}, voucherId={}", userId, voucherId);
>                     rollbackRedisData(voucherId, userId);
>                     seckillMetrics.incrementMqConsumeFail();
>                     return;
>                 }
>
>                 Result deductResult = voucherFeignClient.deductStock(voucherId);
>                 if (!deductResult.getSuccess()) {
>                     log.error("扣减库存失败: voucherId={}, result={}", voucherId, deductResult.getErrorMsg());
>                     rollbackRedisData(voucherId, userId);
>                     seckillMetrics.incrementMqConsumeFail();
>                     return;
>                 }
>
>                 VoucherOrder voucherOrder = new VoucherOrder();
>                 voucherOrder.setId(orderId);
>                 voucherOrder.setUserId(userId);
>                 voucherOrder.setVoucherId(voucherId);
>                 voucherOrder.setStatus(1);
>
>                 int insertResult = voucherOrderMapper.insert(voucherOrder);
>                 if (insertResult > 0) {
>                     seckillMetrics.incrementMqConsumeSuccess();
>                     log.info("订单创建成功: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
>                     stringRedisTemplate.opsForHash().delete(
>                             RedisConstants.SECKILL_STOCK_KEY + "order:detail:" + voucherId,
>                             orderId.toString()
>                     );
>                 } else {
>                     log.error("订单插入失败: orderId={}", orderId);
>                     seckillMetrics.incrementMqConsumeFail();
>                     throw new RuntimeException("订单插入失败");
>                 }
>
>             } finally {
>                 if (lock.isHeldByCurrentThread()) {
>                     lock.unlock();
>                 }
>             }
>
>         } catch (InterruptedException e) {
>             log.error("获取锁被中断: orderId={}", orderId, e);
>             Thread.currentThread().interrupt();
>             seckillMetrics.incrementMqConsumeFail();
>             throw new RuntimeException("获取锁被中断", e);
>         } catch (Exception e) {
>             log.error("处理秒杀订单消息异常: orderId={}, error={}", orderId, e.getMessage(), e);
>             seckillMetrics.incrementMqConsumeFail();
>
>             if (message.getRetryCount() >= MAX_RETRY_COUNT) {
>                 log.error("订单处理重试次数已达上限，需要人工干预: orderId={}, retryCount={}",
>                         orderId, message.getRetryCount());
>             }
>             throw new RuntimeException("订单处理失败", e);
>         }
>     }
>
>     /**
>      * 回滚Redis预扣数据
>      *
>      * 在消息消费失败时调用，用于恢复Redis中的库存和用户购买记录。
>      * 保证Redis预扣数据与数据库最终状态的一致性。
>      *
>      * @param voucherId 优惠券ID
>      * @param userId 用户ID
>      */
>     private void rollbackRedisData(Long voucherId, Long userId) {
>         try {
>             stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
>             stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
>             log.info("Redis数据回滚成功: voucherId={}, userId={}", voucherId, userId);
>         } catch (Exception e) {
>             log.error("Redis数据回滚失败: voucherId={}, userId={}, error={}", voucherId, userId, e.getMessage(), e);
>         }
>     }
> }
>
> package com.hmdp.order.mq;
>
> import com.hmdp.dto.SeckillOrderMessage;
> import lombok.extern.slf4j.Slf4j;
> import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
> import org.apache.rocketmq.spring.core.RocketMQListener;
> import org.springframework.data.redis.core.StringRedisTemplate;
> import org.springframework.stereotype.Component;
>
> import jakarta.annotation.Resource;
>
> @Component
> @RocketMQMessageListener(
>         topic = SeckillOrderProducer.TOPIC_SECKILL_ORDER_DLQ,
>         consumerGroup = "seckill-order-dlq-consumer-group"
> )
> @Slf4j
> public class SeckillOrderDLQConsumer implements RocketMQListener<SeckillOrderMessage> {
>
>     @Resource
>     private StringRedisTemplate stringRedisTemplate;
>
>     @Override
>     public void onMessage(SeckillOrderMessage message) {
>         log.error("死信队列收到消息，订单处理失败需要人工干预: orderId={}, userId={}, voucherId={}, retryCount={}",
>                 message.getOrderId(), message.getUserId(), message.getVoucherId(), message.getRetryCount());
>
>         try {
>             String pendingOrderKey = "seckill:order:pending";
>             String orderInfo = String.format("%d:%d:%d:%d",
>                     message.getOrderId(),
>                     message.getUserId(),
>                     message.getVoucherId(),
>                     System.currentTimeMillis());
>             stringRedisTemplate.opsForList().rightPush(pendingOrderKey, orderInfo);
>             log.info("失败订单已记录到待处理列表: orderId={}", message.getOrderId());
>         } catch (Exception e) {
>             log.error("记录失败订单异常: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
>         }
>     }
> }
>
> package com.hmdp.order.mq;
>
> import com.hmdp.dto.SeckillOrderMessage;
> import lombok.AllArgsConstructor;
> import lombok.Data;
> import lombok.NoArgsConstructor;
> import lombok.extern.slf4j.Slf4j;
> import org.apache.rocketmq.spring.core.RocketMQTemplate;
> import org.springframework.messaging.support.MessageBuilder;
> import org.springframework.stereotype.Component;
>
> import jakarta.annotation.Resource;
>
> /**
>  * 秒杀订单消息生产者
>  *
>  * 负责将秒杀资格校验通过的订单发送到消息队列，实现异步下单流程。
>  * 核心作用：
>  * 1. 流量削峰：将瞬时高并发请求转化为异步消息处理
>  * 2. 解耦：分离秒杀资格校验和订单创建两个关键步骤
>  * 3. 可靠性：提供同步发送和异步发送两种模式，支持重试机制
>  *
>  * 消息主题：
>  * - seckill-order-topic: 正常秒杀订单处理
>  * - seckill-order-dlq-topic: 死信队列，处理失败消息
>  * - stock-sync-topic: 库存同步主题（用于一致性保证）
>  */
> @Component
> @Slf4j
> public class SeckillOrderProducer {
>
>     public static final String TOPIC_SECKILL_ORDER = "seckill-order-topic";
>
>     public static final String TOPIC_SECKILL_ORDER_DLQ = "seckill-order-dlq-topic";
>
>     public static final String TOPIC_STOCK_SYNC = "stock-sync-topic";
>
>     @Resource
>     private RocketMQTemplate rocketMQTemplate;
>
>     /**
>      * 同步发送秒杀订单消息
>      *
>      * 适用于需要立即确认发送结果的场景，保证消息可靠性。
>      * 如果发送失败，会立即返回false，调用方可以相应处理。
>      *
>      * @param message 秒杀订单消息
>      * @return true-发送成功，false-发送失败
>      */
>     public boolean sendSeckillOrderMessage(SeckillOrderMessage message) {
>         try {
>             rocketMQTemplate.syncSend(
>                     TOPIC_SECKILL_ORDER,
>                     MessageBuilder.withPayload(message).build(),
>                     3000
>             );
>             log.info("秒杀订单消息发送成功: orderId={}, userId={}, voucherId={}",
>                     message.getOrderId(), message.getUserId(), message.getVoucherId());
>             return true;
>         } catch (Exception e) {
>             log.error("秒杀订单消息发送失败: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
>             return false;
>         }
>     }
>
>     /**
>      * 异步发送秒杀订单消息
>      *
>      * 适用于高并发场景，不阻塞主线程，通过回调函数处理发送结果。
>      * 即使发送失败，也不会影响用户秒杀资格（Redis已预扣库存）。
>      *
>      * @param message 秒杀订单消息
>      * @return true-提交成功（不代表发送成功），false-提交失败
>      */
>     public boolean sendSeckillOrderMessageAsync(SeckillOrderMessage message) {
>         try {
>             rocketMQTemplate.asyncSend(
>                     TOPIC_SECKILL_ORDER,
>                     MessageBuilder.withPayload(message).build(),
>                     new org.apache.rocketmq.client.producer.SendCallback() {
>                         @Override
>                         public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
>                             log.info("秒杀订单消息异步发送成功: orderId={}, msgId={}",
>                                     message.getOrderId(), sendResult.getMsgId());
>                         }
>
>                         @Override
>                         public void onException(Throwable e) {
>                             log.error("秒杀订单消息异步发送失败: orderId={}, error={}",
>                                     message.getOrderId(), e.getMessage(), e);
>                         }
>                     },
>                     3000
>             );
>             return true;
>         } catch (Exception e) {
>             log.error("秒杀订单消息异步发送异常: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
>             return false;
>         }
>     }
>
>     public void sendToDeadLetterQueue(SeckillOrderMessage message, String reason) {
>         try {
>             message.setRetryCount(message.getRetryCount() + 1);
>             rocketMQTemplate.syncSend(
>                     TOPIC_SECKILL_ORDER_DLQ,
>                     MessageBuilder.withPayload(message)
>                             .setHeader("reason", reason)
>                             .setHeader("retryCount", message.getRetryCount())
>                             .build()
>             );
>             log.warn("订单消息发送到死信队列: orderId={}, reason={}, retryCount={}",
>                     message.getOrderId(), reason, message.getRetryCount());
>         } catch (Exception e) {
>             log.error("发送到死信队列失败: orderId={}, error={}", message.getOrderId(), e.getMessage(), e);
>         }
>     }
>
>     public void sendStockSyncMessage(Long voucherId, Integer stock) {
>         try {
>             rocketMQTemplate.asyncSend(
>                     TOPIC_STOCK_SYNC,
>                     MessageBuilder.withPayload(new StockSyncMessage(voucherId, stock)).build(),
>                     new org.apache.rocketmq.client.producer.SendCallback() {
>                         @Override
>                         public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
>                             log.info("库存同步消息发送成功: voucherId={}, stock={}", voucherId, stock);
>                         }
>
>                         @Override
>                         public void onException(Throwable e) {
>                             log.error("库存同步消息发送失败: voucherId={}, error={}", voucherId, e.getMessage());
>                         }
>                     },
>                     3000
>             );
>         } catch (Exception e) {
>             log.error("库存同步消息发送异常: voucherId={}, error={}", voucherId, e.getMessage(), e);
>         }
>     }
>
>     @Data
>     @AllArgsConstructor
>     @NoArgsConstructor
>     public static class StockSyncMessage {
>         private Long voucherId;
>         private Integer stock;
>     }
> }
>
>
> 我的订单服务通过boolean sendSuccess = seckillOrderProducer.sendSeckillOrderMessageAsync(message);来调用队列，这里使用的是RocketMQ，可以完全替换成RabbitMQ嘛

可以替换，但不是“改一个发送方法”这么简单；我会按你的这段代码逐层对比：生产者、消费者、重试/死信、幂等、事务一致性。

我先指出一个关键点：你现在的代码没有强依赖 RocketMQ 的顺序消息、事务消息或 Tag 过滤，所以从业务功能上看很适合迁移到 RabbitMQ。真正要处理的是“发送确认、消费失败重试、死信、幂等补偿”的语义差异。

可以，**你的这套秒杀订单 MQ 逻辑可以完全替换成 RabbitMQ**。但它不是把：

```java
rocketMQTemplate.asyncSend(...)
```

简单改成：

```java
rabbitTemplate.convertAndSend(...)
```

就结束了。真正要迁移的是 **Topic/队列模型、发送确认、消费重试、死信队列、ACK 语义**。

RabbitMQ 的路由模型是 `Exchange -> Queue`，Direct/Topic/Fanout 等交换机决定消息怎么路由；这和 RocketMQ 的 `topic + consumerGroup` 模型不一样。 RabbitMQ 的可靠性也更依赖 **publisher confirm** 和 **consumer ack**，生产者确认和消费者确认是两套彼此独立的机制。

## 结论

你的场景是：

```java
boolean sendSuccess = seckillOrderProducer.sendSeckillOrderMessageAsync(message);
```

然后消费者异步创建订单。

这种场景用 RabbitMQ 完全没问题，建议替换为：

```text
seckill.order.exchange
        |
routingKey = seckill.order.create
        |
seckill.order.queue
        |
@RabbitListener 消费订单
        |
失败超过 3 次
        |
seckill.order.dlx -> seckill.order.dlq
```

RabbitMQ 的死信机制可以在消息被拒绝且不重新入队、消息过期、队列超长、或 quorum queue 超过 delivery-limit 时把消息转发到 DLX。 Spring Boot 也支持给 `@RabbitListener` 开启消费重试，重试耗尽后消息会被 reject，若配置了 DLX，就会进入死信队列。

---

## 你现在这段 RocketMQ 代码迁移到 RabbitMQ 的对应关系

| RocketMQ | RabbitMQ |
|---|---|
| `RocketMQTemplate` | `RabbitTemplate` |
| `@RocketMQMessageListener` | `@RabbitListener` |
| `topic = seckill-order-topic` | `exchange + routingKey + queue` |
| `consumerGroup` | 通常用 queue 名表达消费组语义 |
| `maxReconsumeTimes = 3` | `listener.simple.retry.max-attempts=3` |
| 手动 DLQ Topic | DLX + DLQ |
| 抛异常触发重试 | 抛异常触发 Spring AMQP 重试 / RabbitMQ nack |
| `retryCount` 字段 | 可保留，也可读取 RabbitMQ `x-death` header |

---

## RabbitMQ 版配置示例

### 1. Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

### 2. application.yml

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

    publisher-confirm-type: correlated
    publisher-returns: true

    template:
      mandatory: true
      retry:
        enabled: true
        max-attempts: 3
        initial-interval: 500ms
        multiplier: 2
        max-interval: 3s

    listener:
      simple:
        acknowledge-mode: manual
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1s
          multiplier: 2
          max-interval: 5s
        default-requeue-rejected: false
```

注意：`convertAndSend` 本身不能直接告诉你“消息最终一定进队列了”。Spring AMQP 文档也说明，发布是异步机制，默认情况下无法路由的消息可能被丢弃，所以需要 publisher confirm / returns。

---

## 3. RabbitMQ 常量

```java
public class SeckillRabbitConstants {

    public static final String EXCHANGE_SECKILL_ORDER = "seckill.order.exchange";
    public static final String QUEUE_SECKILL_ORDER = "seckill.order.queue";
    public static final String ROUTING_KEY_SECKILL_ORDER = "seckill.order.create";

    public static final String EXCHANGE_SECKILL_ORDER_DLX = "seckill.order.dlx";
    public static final String QUEUE_SECKILL_ORDER_DLQ = "seckill.order.dlq";
    public static final String ROUTING_KEY_SECKILL_ORDER_DLQ = "seckill.order.dead";
}
```

---

## 4. RabbitMQ 队列、交换机、死信配置

```java
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeckillRabbitConfig {

    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(
                SeckillRabbitConstants.EXCHANGE_SECKILL_ORDER,
                true,
                false
        );
    }

    @Bean
    public DirectExchange seckillOrderDlxExchange() {
        return new DirectExchange(
                SeckillRabbitConstants.EXCHANGE_SECKILL_ORDER_DLX,
                true,
                false
        );
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder
                .durable(SeckillRabbitConstants.QUEUE_SECKILL_ORDER)
                .deadLetterExchange(SeckillRabbitConstants.EXCHANGE_SECKILL_ORDER_DLX)
                .deadLetterRoutingKey(SeckillRabbitConstants.ROUTING_KEY_SECKILL_ORDER_DLQ)
                .build();
    }

    @Bean
    public Queue seckillOrderDlq() {
        return QueueBuilder
                .durable(SeckillRabbitConstants.QUEUE_SECKILL_ORDER_DLQ)
                .build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder
                .bind(seckillOrderQueue())
                .to(seckillOrderExchange())
                .with(SeckillRabbitConstants.ROUTING_KEY_SECKILL_ORDER);
    }

    @Bean
    public Binding seckillOrderDlqBinding() {
        return BindingBuilder
                .bind(seckillOrderDlq())
                .to(seckillOrderDlxExchange())
                .with(SeckillRabbitConstants.ROUTING_KEY_SECKILL_ORDER_DLQ);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

生产环境里，RabbitMQ 官方更推荐用 policy 配置 DLX，而不是把 `x-dead-letter-exchange` 这类参数硬编码进队列声明，因为硬编码参数变更时通常要重新部署甚至删除重建队列。

---

## 5. RabbitMQ 版 Producer

```java
import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

@Component
@Slf4j
public class SeckillOrderRabbitProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("秒杀订单消息发送到 Broker 成功: correlationId={}",
                        correlationData == null ? null : correlationData.getId());
            } else {
                log.error("秒杀订单消息发送到 Broker 失败: correlationId={}, cause={}",
                        correlationData == null ? null : correlationData.getId(),
                        cause);

                // 这里建议：
                // 1. 记录发送失败日志
                // 2. 写入本地消息表 / Redis 待补偿队列
                // 3. 或回滚 Redis 预扣库存
            }
        });

        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("秒杀订单消息路由失败: exchange={}, routingKey={}, replyCode={}, replyText={}, message={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    returned.getMessage());
        });
    }

    public boolean sendSeckillOrderMessageAsync(SeckillOrderMessage message) {
        try {
            CorrelationData correlationData =
                    new CorrelationData(String.valueOf(message.getOrderId()));

            rabbitTemplate.convertAndSend(
                    SeckillRabbitConstants.EXCHANGE_SECKILL_ORDER,
                    SeckillRabbitConstants.ROUTING_KEY_SECKILL_ORDER,
                    message,
                    correlationData
            );

            log.info("秒杀订单消息已提交发送: orderId={}, userId={}, voucherId={}",
                    message.getOrderId(),
                    message.getUserId(),
                    message.getVoucherId());

            return true;
        } catch (Exception e) {
            log.error("秒杀订单消息提交发送异常: orderId={}, error={}",
                    message.getOrderId(),
                    e.getMessage(),
                    e);
            return false;
        }
    }
}
```

这里要特别注意：这个 `true` 只表示**本地提交发送成功**，不代表消息一定成功落到目标队列。真正的可靠判断要看 `ConfirmCallback` 和 `ReturnsCallback`。

你原来的 RocketMQ 异步发送也有类似问题：

```java
sendSeckillOrderMessageAsync(message)
```

方法返回 `true` 后，后续 `onException` 里只是打日志。如果 Redis 已经预扣库存，但 MQ 最终发送失败，这个订单可能会丢。这个问题换 RabbitMQ 后仍然存在，应该用：

```text
本地消息表 / outbox
或 Redis 待补偿队列
或发送失败时回滚 Redis 预扣库存
```

---

## 6. RabbitMQ 版订单消费者

你的核心业务代码基本可以复用，只需要把注解换成 `@RabbitListener`。

```java
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.utils.RedisConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class SeckillOrderRabbitConsumer {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private VoucherFeignClient voucherFeignClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private SeckillMetrics seckillMetrics;

    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final int MAX_RETRY_COUNT = 3;

    @RabbitListener(queues = SeckillRabbitConstants.QUEUE_SECKILL_ORDER)
    public void onMessage(SeckillOrderMessage message,
                          Message rawMessage,
                          Channel channel) throws IOException {

        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();

        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        log.info("开始处理秒杀订单消息: orderId={}, userId={}, voucherId={}",
                orderId, userId, voucherId);

        try {
            doCreateOrder(message);

            // 业务真正成功后，手动 ACK
            channel.basicAck(deliveryTag, false);

            seckillMetrics.incrementMqConsumeSuccess();
            log.info("订单消息消费成功，已手动ACK: orderId={}", orderId);

        } catch (BusinessNoRetryException e) {
            // 业务明确不可重试，例如用户已购买、库存确实不足
            log.warn("订单消息业务失败，不重试，直接ACK: orderId={}, reason={}",
                    orderId, e.getMessage());

            seckillMetrics.incrementMqConsumeFail();

            // 不重试，但消息已经处理完毕，所以 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("订单消息消费异常: orderId={}, error={}",
                    orderId, e.getMessage(), e);

            seckillMetrics.incrementMqConsumeFail();

            handleRetryOrDeadLetter(message, rawMessage, channel, deliveryTag, e);
        }
    }

    private void doCreateOrder(SeckillOrderMessage message) throws InterruptedException {
        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        String lockKey = "lock:order:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
        if (!locked) {
            throw new RuntimeException("获取订单锁失败");
        }

        try {
            VoucherOrder existingOrder = voucherOrderMapper.selectById(orderId);
            if (existingOrder != null) {
                log.info("订单已存在，幂等跳过: orderId={}", orderId);
                return;
            }

            Long count = voucherOrderMapper.selectCount(
                    new LambdaQueryWrapper<VoucherOrder>()
                            .eq(VoucherOrder::getUserId, userId)
                            .eq(VoucherOrder::getVoucherId, voucherId)
            );

            if (count > 0) {
                log.warn("用户已购买过该优惠券: userId={}, voucherId={}", userId, voucherId);

                // 注意：这里建议只回滚库存，不要删除用户购买标记
                rollbackRedisStockOnly(voucherId, userId);

                throw new BusinessNoRetryException("用户已购买过该优惠券");
            }

            Result deductResult = voucherFeignClient.deductStock(voucherId);
            if (!deductResult.getSuccess()) {
                log.error("扣减库存失败: voucherId={}, result={}",
                        voucherId, deductResult.getErrorMsg());

                rollbackRedisData(voucherId, userId);

                // 如果明确是库存不足，属于不可重试业务失败
                throw new BusinessNoRetryException("库存不足");
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setStatus(1);

            int insertResult = voucherOrderMapper.insert(voucherOrder);
            if (insertResult <= 0) {
                throw new RuntimeException("订单插入失败");
            }

            stringRedisTemplate.opsForHash().delete(
                    RedisConstants.SECKILL_STOCK_KEY + "order:detail:" + voucherId,
                    orderId.toString()
            );

            log.info("订单创建成功: orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void handleRetryOrDeadLetter(SeckillOrderMessage message,
                                         Message rawMessage,
                                         Channel channel,
                                         long deliveryTag,
                                         Exception e) throws IOException {

        Integer retryCount = rawMessage.getMessageProperties()
                .getHeader("x-retry-count");

        if (retryCount == null) {
            retryCount = 0;
        }

        Long orderId = message.getOrderId();

        if (retryCount < MAX_RETRY_COUNT) {
            int nextRetryCount = retryCount + 1;

            try {
                rabbitTemplate.convertAndSend(
                        SeckillRabbitConstants.EXCHANGE_SECKILL_ORDER,
                        SeckillRabbitConstants.ROUTING_KEY_SECKILL_ORDER,
                        message,
                        msg -> {
                            msg.getMessageProperties()
                                    .setHeader("x-retry-count", nextRetryCount);
                            return msg;
                        }
                );

                // 重试消息重新发送成功后，ACK 当前失败消息
                channel.basicAck(deliveryTag, false);

                log.warn("订单消息处理失败，已重新投递重试: orderId={}, retryCount={}",
                        orderId, nextRetryCount);

            } catch (Exception sendRetryException) {
                log.error("重新投递重试消息失败，当前消息重新入队: orderId={}, error={}",
                        orderId, sendRetryException.getMessage(), sendRetryException);

                // 重试消息发送失败，当前消息不能丢，重新入队
                channel.basicNack(deliveryTag, false, true);
            }

        } else {
            log.error("订单消息重试次数达到上限，进入死信队列: orderId={}, retryCount={}",
                    orderId, retryCount);

            // false 表示不重新入队；队列配置了 DLX 后，会进入死信队列
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void rollbackRedisData(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.opsForValue()
                    .increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);

            stringRedisTemplate.opsForSet()
                    .remove("seckill:order:" + voucherId, userId.toString());

            log.info("Redis预扣数据回滚成功: voucherId={}, userId={}", voucherId, userId);
        } catch (Exception e) {
            log.error("Redis预扣数据回滚失败: voucherId={}, userId={}, error={}",
                    voucherId, userId, e.getMessage(), e);
        }
    }

    private void rollbackRedisStockOnly(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.opsForValue()
                    .increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);

            log.info("仅回滚Redis库存，保留用户购买标记: voucherId={}, userId={}",
                    voucherId, userId);
        } catch (Exception e) {
            log.error("Redis库存回滚失败: voucherId={}, userId={}, error={}",
                    voucherId, userId, e.getMessage(), e);
        }
    }

    private static class BusinessNoRetryException extends RuntimeException {
        public BusinessNoRetryException(String message) {
            super(message);
        }
    }
}
```

---

## 7. RabbitMQ 版死信消费者

```java
import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
@Slf4j
public class SeckillOrderRabbitDLQConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = SeckillRabbitConstants.QUEUE_SECKILL_ORDER_DLQ)
    public void onMessage(SeckillOrderMessage message, Message rawMessage) {
        log.error("死信队列收到秒杀订单消息: orderId={}, userId={}, voucherId={}, headers={}",
                message.getOrderId(),
                message.getUserId(),
                message.getVoucherId(),
                rawMessage.getMessageProperties().getHeaders());

        try {
            String pendingOrderKey = "seckill:order:pending";

            String orderInfo = String.format("%d:%d:%d:%d",
                    message.getOrderId(),
                    message.getUserId(),
                    message.getVoucherId(),
                    System.currentTimeMillis());

            stringRedisTemplate.opsForList().rightPush(pendingOrderKey, orderInfo);

            log.info("失败订单已记录到待处理列表: orderId={}", message.getOrderId());
        } catch (Exception e) {
            log.error("记录失败订单异常: orderId={}, error={}",
                    message.getOrderId(),
                    e.getMessage(),
                    e);
        }
    }
}
```

RabbitMQ 死信消息会带有 `x-death` 等 header，用来记录死信来源、原因、次数等信息。 所以迁移后，你原来的 `message.getRetryCount()` 可以保留，但更推荐结合 `x-death` 观察真实死信历史。

---

## 你这段代码迁移前建议顺手修的几个问题

第一，**获取锁失败不能直接 return**。

你现在 RocketMQ 代码里：

```java
if (!locked) {
    log.warn("获取订单锁失败，可能正在处理中: orderId={}", orderId);
    return;
}
```

这会导致 MQ 认为消费成功，消息可能直接丢掉。RabbitMQ 版建议改成：

```java
throw new RuntimeException("获取订单锁失败");
```

让 MQ 重试。

第二，**`deductStock` 成功但 `insert order` 失败会出大问题**。

现在流程是：

```text
扣数据库库存成功
插入订单失败
抛异常
MQ 重试
再次扣库存
```

这可能导致库存被重复扣减。你应该让库存扣减接口支持幂等，例如：

```java
deductStock(voucherId, orderId)
```

库存服务用 `orderId` 做扣减流水去重。

第三，**一人一单必须加数据库唯一索引**。

你现在用：

```java
selectCount(userId, voucherId)
```

这个只能做业务判断，不能抗并发。建议数据库加唯一索引：

```sql
ALTER TABLE voucher_order
ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

分布式锁按 `orderId` 加锁并不能防住同一个用户用不同 `orderId` 重复购买。

第四，**异步发送失败不能只打日志**。

无论 RocketMQ 还是 RabbitMQ，只要你的 Redis 已经预扣库存，就必须处理 MQ 发送失败：

```text
方案一：发送失败回滚 Redis 库存和用户购买标记
方案二：写本地消息表，由定时任务补发
方案三：Redis 记录 pending message，后台补偿
```

否则会出现：

```text
用户抢购成功
Redis 库存已扣
MQ 消息没发出去
数据库没有订单
```

---

## 最推荐的迁移方案

你的代码可以替换成 RabbitMQ，而且业务复杂度不高。我建议采用：

```text
RabbitTemplate + publisher confirm + returns
@RabbitListener + Spring retry 3 次
DirectExchange + DLX + DLQ
订单表 orderId 幂等
userId + voucherId 唯一索引
库存扣减接口加 orderId 幂等
MQ 发送失败补偿机制
```

也就是说，**MQ 层可以完全换成 RabbitMQ，但一致性逻辑不能只靠 MQ，还是要靠数据库唯一约束、幂等扣库存、发送失败补偿来兜底**。
