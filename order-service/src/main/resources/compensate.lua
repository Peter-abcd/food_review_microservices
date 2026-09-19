-- 秒杀 Redis 预扣补偿 —— 原子版
--
-- 为什么补偿要做三件事（缺任何一件都会留下悬挂状态）：
--   ① 库存 +1       把预扣的库存还回去
--   ② SREM 用户     去掉"一人一单"标记，否则该用户再也下不了这一单
--   ③ DEL 明细哈希  清掉 seckill.lua 写入的订单明细
--
-- 为什么必须幂等：
--   有 4 条路径都会触发补偿，同一条订单可能被补偿多次
--   （SeckillOrderProducer 的提交异常/confirm 回调、RabbitConfirmCallback 的
--     returns 回调、SeckillOrderConsumer 的消费重试耗尽；其中 confirm 与 returns
--     是同一次路由失败的两个回调，必然都会触发）。
--   重复补偿 = 库存加两次 = 凭空多出库存 = 超卖。
--
-- 为什么必须放进 Lua 而不是在 Java 里依次发命令：
--   早期实现是「SETNX 抢标记 + 三次独立命令」，两个毛病：
--     1) 三件事不是原子的：执行到一半异常会留下"部分补偿"状态；
--     2) 异常分支里删掉标记放行重试 —— 于是已执行的部分会被【再做一遍】，库存被加两次。
--        也就是说那个"幂等门"在"失败重试"这条路上是漏的。
--   放进 Lua 之后，「抢标记 + 三件事」在 Redis 里是一段不会被打断的执行：
--     · 两条路径抢同一条订单的补偿 → 只有拿到标记的那个执行，另一个拿到 0 直接跳过；
--     · 不存在"部分补偿后被重试"的窗口，因为标记永远先写。
--
-- ★ 标记先写、且失败时不删，是有意为之：
--   万一脚本中途出错（INCR/SREM 的类型错误等），标记已经存在 → 不会有任何重试把它再做一遍。
--   此时的结果是"库存少还了一次" —— 这是【安全方向】（少卖，不会超卖），由对账兜底；
--   反过来若删掉标记放行重试，就是"多加库存" —— 【危险方向】（不可逆的超卖）。
--
-- KEYS[1] = seckill:compensated:{orderId}      幂等标记
-- KEYS[2] = seckill:stock:{voucherId}          库存
-- KEYS[3] = seckill:order:{voucherId}          一人一单集合
-- KEYS[4] = seckill:order:detail:{voucherId}   订单明细哈希
-- ARGV[1] = userId
-- ARGV[2] = reason（写进标记的值，事后能看出是谁补的、因为什么补的）
-- ARGV[3] = 标记 TTL（秒）
--
-- 返回：1 = 本次真正执行了补偿；0 = 已被补偿过，幂等跳过

-- 先抢标记：没抢到说明别的路径已经补过了，直接返回，绝不再动库存
local acquired = redis.call('SET', KEYS[1], ARGV[2], 'NX', 'EX', ARGV[3])
if not acquired then
    return 0
end

redis.call('INCR', KEYS[2])              -- ① 库存 +1
redis.call('SREM', KEYS[3], ARGV[1])     -- ② 去掉一人一单标记
redis.call('DEL', KEYS[4])               -- ③ 清掉订单明细哈希

return 1
