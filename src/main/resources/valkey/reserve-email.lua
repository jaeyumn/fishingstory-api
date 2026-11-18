-- KEYS: IP window, email window, cooldown, reservation. ARGV: unique operation ID.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local hour = 3600000
local day = 86400000
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - hour)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - day)
local wait = 0
local ipCount = redis.call('ZCARD', KEYS[1])
if ipCount >= 30 then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    wait = tonumber(oldest[2]) + hour - now
else
    -- Requests blocked by email rules still consume the IP request allowance.
    redis.call('ZADD', KEYS[1], now, ARGV[1])
    redis.call('PEXPIRE', KEYS[1], hour)
end
wait = math.max(wait, redis.call('PTTL', KEYS[3]))
if redis.call('ZCARD', KEYS[2]) >= 10 then
    local oldest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
    wait = math.max(wait, tonumber(oldest[2]) + day - now)
end
if wait > 0 then return wait end
redis.call('ZADD', KEYS[2], now, ARGV[1])
redis.call('PEXPIRE', KEYS[2], day)
redis.call('SET', KEYS[3], ARGV[1], 'PX', 60000)
redis.call('SET', KEYS[4], 'PENDING', 'PX', day)
return 0
