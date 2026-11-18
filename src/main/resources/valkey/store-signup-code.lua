-- KEYS: current signup code, email cooldown, reservation. ARGV: operation ID, code.
-- Only the latest successful reservation may replace the code.
if redis.call('GET', KEYS[2]) ~= ARGV[1] or redis.call('GET', KEYS[3]) ~= 'PENDING' then
    return -1
end
if redis.call('HGET', KEYS[1], 'operationId') == ARGV[1] then return -2 end
local time = redis.call('TIME')
local expires = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) + 300000
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'operationId', ARGV[1], 'code', ARGV[2], 'attempts', '0', 'expiresAt', expires)
redis.call('PEXPIRE', KEYS[1], 300000)
return expires
