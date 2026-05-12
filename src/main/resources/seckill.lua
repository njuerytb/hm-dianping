-- 接收参数
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 拼接key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1. 查询库存（修复nil问题）
local stock = redis.call('get', stockKey)
-- 关键：不存在则赋值为0，避免nil比较报错
if stock == false or stock == nil then
    stock = 0
end

-- 2. 判断库存是否充足
if tonumber(stock) <= 0 then
    return 1  -- 库存不足
end

-- 3. 判断用户是否已经下单
if redis.call('sismember', orderKey, userId) == 1 then
    return 2  -- 重复下单
end

-- 4. 扣减库存
redis.call('incrby', stockKey, -1)

-- 5. 记录用户订单（你这里写错了！必须是orderKey，不是stockKey）
redis.call('sadd', orderKey, userId)

-- 6. 成功
return 0