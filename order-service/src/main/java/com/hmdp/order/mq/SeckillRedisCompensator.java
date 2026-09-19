package com.hmdp.order.mq;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀 Redis 预扣补偿器
 *
 * 为什么需要补偿：
 *   秒杀是「Redis 先预扣 → MQ 异步落库」两段式。Redis 扣成功之后，
 *   如果消息投递失败、或消费重试耗尽，Redis 里就留下悬挂状态：
 *     库存白扣了 1 + 该用户被标记成"已下单"（这张券他再也抢不到）。
 *   补偿就是把这三件事倒回去：库存 +1、SREM 去掉一人一单标记、清掉订单明细哈希。
 *
 * 为什么必须幂等：
 *   有 4 条路径都会触发补偿，同一条订单可能被补偿多次 ——
 *     ① SeckillOrderProducer 提交阶段抛异常（broker 根本没收到）
 *     ② SeckillOrderProducer 的单条消息 confirm 回调判定投递失败
 *     ③ RabbitConfirmCallback 的全局 returns 回调（消息路由不到任何队列）
 *     ④ SeckillOrderConsumer 消费重试耗尽（且 DB 库存未扣减）
 *   其中 ② 和 ③ 是【同一次路由失败】的两个回调，必然都会触发。
 *   重复补偿会让库存 +2 → 凭空多出库存 → 超卖。
 *
 * 为什么放在 Lua 里执行（本类的核心改动）：
 *   早期实现是「SETNX 抢标记 + 三次独立 Redis 命令」，两个毛病：
 *     1) 三件事不是原子的：执行到一半抛异常会留下"部分补偿"状态；
 *     2) 异常分支里删掉标记放行重试 —— 已执行的那部分会被【再做一遍】，库存被加两次。
 *        也就是说那个"幂等门"在"失败重试"这条路上是漏的。
 *   改成 Lua 后，「抢标记 + 三件事」在 Redis 里是一段不会被打断的执行：
 *     · 两条路径抢同一条订单 → 只有拿到标记的那个真正执行，另一个返回 0 直接跳过；
 *     · 不存在"部分补偿后被重试"的窗口（标记永远先写）。
 *   标记先写、且失败时【不删】是有意为之：万一脚本中途出错，宁可"少还一次库存"
 *   （安全方向，少卖不会超卖，可由对账兜底），也不要"多加库存"（危险方向，不可逆的超卖）。
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

    /** 补偿脚本：抢标记 + 库存+1 + SREM 一人一单 + 清明细，原子执行 */
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT;
    static {
        COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("compensate.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    /**
     * 补偿一次秒杀预扣（原子执行）。
     *
     * @return true-本次调用真正执行了补偿；false-已被其他路径补偿过（幂等跳过）或执行失败
     */
    public boolean compensate(Long orderId, Long userId, Long voucherId, String reason) {
        if (orderId == null || userId == null || voucherId == null) {
            log.error("补偿参数不完整，拒绝执行: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
            return false;
        }

        String markKey = COMPENSATED_KEY + orderId;
        List<String> keys = Arrays.asList(
                markKey,
                RedisConstants.SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_ORDER_KEY + voucherId,
                RedisConstants.SECKILL_ORDER_DETAIL_KEY + voucherId);

        try {
            Long executed = stringRedisTemplate.execute(
                    COMPENSATE_SCRIPT,
                    keys,
                    userId.toString(),
                    reason,
                    String.valueOf(TimeUnit.HOURS.toSeconds(MARK_TTL_HOURS)));

            if (executed != null && executed == 1L) {
                log.warn("Redis 预扣补偿成功: orderId={}, userId={}, voucherId={}, 原因={}",
                        orderId, userId, voucherId, reason);
                return true;
            }

            log.warn("该订单已补偿过，幂等跳过: orderId={}, 已有标记原因={}",
                    orderId, stringRedisTemplate.opsForValue().get(markKey));
            return false;
        } catch (Exception e) {
            // 【不要】在这里删幂等标记。脚本是原子执行的，"抛异常"意味着可能已经执行了一部分，
            // 删标记放行重试会把已执行的部分再做一次（库存多加 → 超卖）。
            // 宁可少还一次库存（安全方向，可由对账兜底），也不要多加库存（危险方向）。
            // 补偿失败必须大声报出来，否则只能靠人工对账。
            log.error("Redis 预扣补偿失败！需人工介入 orderId={}, userId={}, voucherId={}, error={}",
                    orderId, userId, voucherId, e.getMessage(), e);
            return false;
        }
    }
}
