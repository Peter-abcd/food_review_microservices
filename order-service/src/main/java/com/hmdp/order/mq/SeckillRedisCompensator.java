package com.hmdp.order.mq;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀 Redis 预扣补偿器
 *
 * 为什么需要单独抽出来：
 *   生产者感知"消息没进 broker"有两条路径 ——
 *     a) confirm 回调 ack=false（broker 没收到）
 *     b) return 回调（broker 收到了，但路由不到任何队列）
 *   这两条路径可能【同时】触发（消息路由失败时，broker 的 confirm 仍是 ack=true，
 *   而 ReturnCallback 也会触发；启用 mandatory + correlated 时两者存在竞态）。
 *   如果两处各自补偿一次，库存就会被多加两次 —— 直接超卖。
 *
 * 所以补偿必须幂等：用 SETNX 抢占"该订单已补偿"的标记，只有一个执行者生效。
 */
@Component
@Slf4j
public class SeckillRedisCompensator {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 补偿幂等标记，key = seckill:compensated:{orderId} */
    public static final String COMPENSATED_KEY = "seckill:compensated:";

    /** 幂等标记保留时长（足够覆盖重试+人工处理窗口） */
    private static final long MARK_TTL_HOURS = 24L;

    /**
     * 补偿一次秒杀预扣。
     *
     * 执行内容（必须成组，缺任何一个都会留下悬挂状态）：
     *   stock  +1   把预扣的库存还回去
     *   SREM  用户   去掉一人一单标记，否则该用户再也下不了这一单
     *   DEL   明细   清掉 seckill.lua 写入的订单明细哈希
     *
     * @return true-本次调用真正执行了补偿；false-已被其他路径补偿过（幂等跳过）
     */
    public boolean compensate(Long orderId, Long userId, Long voucherId, String reason) {
        if (orderId == null || userId == null || voucherId == null) {
            log.error("补偿参数不完整，拒绝执行: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
            return false;
        }

        String markKey = COMPENSATED_KEY + orderId;
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(markKey, reason, MARK_TTL_HOURS, TimeUnit.HOURS);

        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("该订单已补偿过，幂等跳过: orderId={}, 已有标记原因={}",
                    orderId, stringRedisTemplate.opsForValue().get(markKey));
            return false;
        }

        try {
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.opsForSet()
                    .remove(RedisConstants.SECKILL_ORDER_KEY + voucherId, userId.toString());
            // 注意：必须用 RedisTemplate.delete() 删除整个哈希 key。
            // opsForHash().delete(key) 不传 field 时发出的命令是 "HDEL key"（无参数），
            // Redis 会直接报错，导致明细泄漏 —— 这个坑踩过。
            stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_DETAIL_KEY + voucherId);

            log.warn("Redis 预扣补偿成功: orderId={}, userId={}, voucherId={}, 原因={}",
                    orderId, userId, voucherId, reason);
            return true;
        } catch (Exception e) {
            // 补偿失败必须大声报出来，否则只能靠人工对账
            log.error("Redis 预扣补偿失败！需人工介入 orderId={}, userId={}, voucherId={}, error={}",
                    orderId, userId, voucherId, e.getMessage(), e);
            // 回滚幂等标记，允许后续路径重试补偿
            stringRedisTemplate.delete(markKey);
            return false;
        }
    }
}
