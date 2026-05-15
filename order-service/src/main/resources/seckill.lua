local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local orderDetailKey = 'seckill:order:detail:' .. voucherId

local stock = tonumber(redis.call('GET', stockKey))
if stock == nil then
    return 1
end

if stock <= 0 then
    return 1
end

if redis.call('SISMEMBER', orderKey, userId) == 1 then
    return 2
end

redis.call('DECR', stockKey)

redis.call('SADD', orderKey, userId)

local orderInfo = cjson.encode({
    voucherId = voucherId,
    userId = userId,
    orderId = orderId
})

redis.call('HSET', orderDetailKey, orderId, orderInfo)

return 0
