local quotaKey = KEYS[1]
local orderKey = KEYS[2]
local releaseKey = KEYS[3]

local userId = ARGV[1]
local releaseQuota = tonumber(ARGV[2])
local markerTtlSeconds = tonumber(ARGV[3])

-- A lost Redis response can make the caller retry after the Lua script already
-- committed. The order-scoped marker prevents a second quota increment.
if redis.call('exists', releaseKey) == 1 then
    return 0
end

if releaseQuota == 1 and redis.call('exists', quotaKey) == 0 then
    return -1
end

if releaseQuota == 1 then
    redis.call('incrby', quotaKey, 1)
end
redis.call('srem', orderKey, userId)
redis.call('set', releaseKey, '1', 'EX', markerTtlSeconds)
return 1
