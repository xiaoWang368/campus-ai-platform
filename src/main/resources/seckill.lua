--优惠券id
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]
--订单id
local orderId = ARGV[3]

--数据key
--库存
local stockkey = 'seckill:stock:'..voucherId
--订单
local orderKey = 'seckill:order:'..voucherId

if (tonumber(redis.call('get', stockkey)) <= 0) then
    --库存不存在
    return 1
end
if(redis.call('sismember', orderKey, userId) == 1) then
    --用户已经下过单,不允许重复下单
    return 2
end
--扣减库存
redis.call('incrby', stockkey, -1)
--下单，保存用户信息
redis.call('sadd',orderKey,userId)
--发送消息
redis.call('xadd', 'stream.orders', '*',  'voucherId', voucherId, 'userId', userId,'id', orderId)
return 0