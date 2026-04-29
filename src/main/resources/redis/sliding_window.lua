-- KEYS[1] = bucket key
-- ARGV[1] = now_ms
-- ARGV[2] = window_ms
-- ARGV[3] = max_requests
-- ARGV[4] = unique member id (caller provides "<now>:<random>" to avoid ZADD collision)
-- Returns: { allowed (1|0), count_after, remaining }

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local max = tonumber(ARGV[3])
local member = ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

local count = redis.call('ZCARD', key)

if count >= max then
    return { 0, count, 0 }
end

redis.call('ZADD', key, now, member)
redis.call('PEXPIRE', key, window + 1000)

return { 1, count + 1, max - count - 1 }
