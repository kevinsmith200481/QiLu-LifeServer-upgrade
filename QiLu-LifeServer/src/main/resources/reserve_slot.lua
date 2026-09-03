local slotId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local quotaKey = 'appointment:quota:' .. slotId
local orderKey = 'appointment:order:' .. slotId
local quota = redis.call('get', quotaKey)

if (quota == false or tonumber(quota) <= 0) then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

redis.call('incrby', quotaKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.appointment-orders', '*', 'id', orderId, 'userId', userId, 'slotId', slotId)
return 0
