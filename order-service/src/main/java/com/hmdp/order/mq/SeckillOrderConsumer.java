package com.hmdp.order.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.config.RabbitMqConfig;
import com.hmdp.order.config.SeckillProperties;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.utils.MqConstants;
import com.hmdp.utils.RedisConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消息消费者
 *
 * 处理流程：
 *   1. 取分布式锁，保证同一订单串行处理
 *   2. 幂等检查（订单已存在则直接跳过）
 *   3. 一人一单终态校验（失败则回滚 Redis 并确认，不重试）
 *   4. Feign 调 voucher-service 扣减数据库库存
 *   5. 订单落库
 *   6. 清理 Lua 写入的 Redis 订单明细
 *
 * 失败处理（本次改造重点）：
 *   - 不再使用 basicNack(requeue=true)。那是"立刻、无限"重投，没有退避也没有上限，
 *     业务异常时消息会在队列里高速空转，把 CPU 和日志打满。
 *   - 改为：重试计数放 Redis，nack(requeue=false) 让消息经主队列 DLX 进入重试队列，
 *     等 TTL 到期后自动回到主队列 —— 这样才有"间隔"，且次数可计数、有上限。
 *   - 超过上限后投递死信队列并 ack，同时按情况回滚 Redis 预扣，避免"库存扣了、订单没建"。
 *
 * 关键陷阱（踩过的坑）：
 *   手动 ACK 模式下，如果异常发生在 listener 方法被调用【之前】（例如消息转换失败），
 *   这里的 catch 根本不会执行，消息会永久卡在 unacked 状态 ——
 *   既不被重投、也不进死信、也不被 ack。所以消息转换器的配置必须正确
 *   （见 RabbitMqConfig#jsonMessageConverter）。
 */
@Component
@Slf4j
public class SeckillOrderConsumer {

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
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillRedisCompensator compensator;

    /**
     * 可动态调整的参数（Nacos 下发，改完不需要重启服务）。
     *
     * 为什么不给消费者本身加 @RefreshScope：它身上挂着 @RabbitListener，
     * 监听器 bean 被刷新会重建容器，可能丢掉正在处理的消息。
     * 把「会变的配置」隔离到 SeckillProperties，刷新时只重建那个小 bean，
     * 这里用到的时候现取即可。
     */
    @Resource
    private SeckillProperties seckillProperties;

    @RabbitListener(queues = MqConstants.SECKILL_ORDER_QUEUE)
    public void onMessage(SeckillOrderMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        log.info("开始处理秒杀订单消息: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);

        RLock lock = redissonClient.getLock("lock:order:" + orderId);
        boolean locked = false;

        // 数据库库存是否已经真实扣减成功。决定最终失败时能不能安全回滚 Redis 预扣。
        boolean dbStockDeducted = false;

        try {
            locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
            if (!locked) {
                // 拿不到锁 = 同订单正在被处理，属于可重试的瞬时状态，交给重试队列
                throw new IllegalStateException("获取订单锁失败，可能正在处理中");
            }

            // ---------- 1. 幂等：订单已存在 ----------
            VoucherOrder existingOrder = voucherOrderMapper.selectById(orderId);
            if (existingOrder != null) {
                log.info("订单已存在，幂等跳过: orderId={}", orderId);
                seckillMetrics.incrementMqConsumeSuccess();
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ---------- 2. 一人一单终态校验 ----------
            Long count = voucherOrderMapper.selectCount(
                    new LambdaQueryWrapper<VoucherOrder>()
                            .eq(VoucherOrder::getUserId, userId)
                            .eq(VoucherOrder::getVoucherId, voucherId)
            );
            if (count != null && count > 0) {
                log.warn("一人一单校验失败，回滚 Redis 库存: userId={}, voucherId={}", userId, voucherId);
                // 保留用户购买标记（用户确实已有订单），只把多扣的库存还回去
                rollbackRedisStockOnly(voucherId);
                seckillMetrics.incrementMqConsumeFail();
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ---------- 3. 扣减数据库库存（跨服务） ----------
            Result deductResult = voucherFeignClient.deductStock(voucherId);
            if (!deductResult.getSuccess()) {
                // 可能是库存真的不足，也可能是 voucher-service 抖动/降级，后者重试有意义
                throw new IllegalStateException("扣减库存失败: " + deductResult.getErrorMsg());
            }
            dbStockDeducted = true;

            // ---------- 4. 订单落库 ----------
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setStatus(1);

            if (voucherOrderMapper.insert(voucherOrder) <= 0) {
                // 这里【不要】再自己 basicNack：外层 catch 会对同一个 deliveryTag 再操作一次，
                // 导致 RabbitMQ 报 PRECONDITION_FAILED: unknown delivery tag 并关闭整个 channel。
                // 抛出去，让唯一的 catch 决定 ack / nack。
                throw new IllegalStateException("订单插入失败");
            }

            // ---------- 5. 清理 Redis 订单明细 ----------
            // key 必须与 seckill.lua 写入的一致：seckill:order:detail:{voucherId}
            stringRedisTemplate.opsForHash().delete(
                    RedisConstants.SECKILL_ORDER_DETAIL_KEY + voucherId,
                    orderId.toString()
            );

            log.info("订单创建成功: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
            seckillMetrics.incrementMqConsumeSuccess();
            channel.basicAck(deliveryTag, false);

        } catch (InterruptedException e) {
            // 线程被中断不是业务失败，直接重投
            Thread.currentThread().interrupt();
            log.error("获取锁被中断: orderId={}", orderId, e);
            seckillMetrics.incrementMqConsumeFail();
            channel.basicNack(deliveryTag, false, true);

        } catch (Exception e) {
            seckillMetrics.incrementMqConsumeFail();
            handleFailure(message, channel, deliveryTag, dbStockDeducted, e);

        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 失败处理：有限次延迟重试，超过上限转死信。
     */
    private void handleFailure(SeckillOrderMessage message, Channel channel, long deliveryTag,
                               boolean dbStockDeducted, Exception cause) throws IOException {

        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        String retryKey = RedisConstants.SECKILL_RETRY_COUNT_KEY + orderId;
        Long attempts = stringRedisTemplate.opsForValue().increment(retryKey);
        if (attempts != null && attempts == 1L) {
            stringRedisTemplate.expire(retryKey, seckillProperties.getRetryCountTtlMinutes(), TimeUnit.MINUTES);
        }

        log.error("秒杀订单处理失败: orderId={}, 第 {} 次尝试, error={}",
                orderId, attempts, cause.getMessage(), cause);

        // 每次现取，Nacos 改了值立刻生效（@RefreshScope 会重建 SeckillProperties）
        int maxAttempts = seckillProperties.getRetryMaxAttempts();

        // ---------- 还能重试 ----------
        if (attempts != null && attempts < maxAttempts) {
            log.warn("第 {}/{} 次尝试失败，{}ms 后经重试队列重新投递 orderId={}",
                    attempts, maxAttempts, RabbitMqConfig.SECKILL_RETRY_TTL_MS, orderId);
            // requeue=false：交给主队列的 DLX -> 重试队列 -> TTL 到期 -> 回主队列
            // requeue=true 会绕过 TTL/DLX，等于没有延迟、没有上限
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        // ---------- 重试耗尽，转死信 ----------
        log.error("已达最大尝试次数 {} 次仍失败，转入死信队列等待人工/对账处理 orderId={}, userId={}, voucherId={}",
                maxAttempts, orderId, userId, voucherId);

        seckillOrderProducer.sendToDeadLetterQueue(message, cause.getMessage());

        if (!dbStockDeducted) {
            // 数据库库存没被扣过，回滚 Redis 预扣是安全的，用户还能重新下单
            log.warn("数据库库存未扣减，回滚 Redis 预扣 orderId={}", orderId);
            // 幂等补偿：confirm/return 回调可能已经补偿过，重复补偿会导致库存多加
            compensator.compensate(orderId, userId, voucherId, "消费重试耗尽且数据库库存未扣减");
        } else {
            // 数据库库存已扣、订单没落库 —— 不能简单回滚 Redis，否则会超卖。
            // 交给死信队列 + SeckillConsistencyService 对账。这行日志必须能被监控抓到。
            log.error("【数据不一致】数据库库存已扣减但订单未落库，需对账 orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);
        }

        stringRedisTemplate.delete(retryKey);
        channel.basicAck(deliveryTag, false);
    }

    /**
     * 仅回滚 Redis 库存，保留用户购买标记。
     *
     * 用于"用户确实已经下过单"的场景 —— 标记要留着（防止再次下单），只把多扣的库存还回去。
     */
    private void rollbackRedisStockOnly(Long voucherId) {
        try {
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            log.info("仅回滚 Redis 库存，保留用户购买标记: voucherId={}", voucherId);
        } catch (Exception e) {
            log.error("Redis 库存回滚失败: voucherId={}, error={}", voucherId, e.getMessage(), e);
        }
    }
}
