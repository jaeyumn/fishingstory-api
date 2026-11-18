-- KEYS: normalized email hash, canonical IP hash. ARGV: unique attempt ID.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local window = 900000
local limits = {10, 50}
local wait = 0
for i = 1, 2 do
    redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now - window)
    if redis.call('ZCARD', KEYS[i]) >= limits[i] then
        local oldest = redis.call('ZRANGE', KEYS[i], 0, 0, 'WITHSCORES')
        wait = math.max(wait, tonumber(oldest[2]) + window - now)
    end
end
if wait > 0 then return wait end
for i = 1, 2 do
    redis.call('ZADD', KEYS[i], now, ARGV[1])
    redis.call('PEXPIRE', KEYS[i], window)
end
return 0
