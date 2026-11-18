-- KEYS: email window, reservation. ARGV: operation ID, CONFIRM or RELEASE.
local state = redis.call('GET', KEYS[2])
if state == 'CONFIRMED' then
    if ARGV[2] == 'CONFIRM' then return 1 else return 0 end
end
if state ~= 'PENDING' then return 0 end
if ARGV[2] == 'RELEASE' then
    redis.call('ZREM', KEYS[1], ARGV[1])
    redis.call('DEL', KEYS[2])
    return 1
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- Count accepted mail for 24 hours from confirmation; retries never extend it.
redis.call('ZADD', KEYS[1], now, ARGV[1])
redis.call('PEXPIRE', KEYS[1], 86400000)
redis.call('SET', KEYS[2], 'CONFIRMED', 'PX', 86400000)
return 1
