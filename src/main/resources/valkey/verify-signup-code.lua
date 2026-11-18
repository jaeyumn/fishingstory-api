-- KEYS: current code, new grant (email-bound token hash). ARGV: submitted code.
-- Results: expiry timestamp, -1 missing/expired, -2 mismatch, -3 attempts exhausted.
local stored = redis.call('HGET', KEYS[1], 'code')
if not stored then return -1 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expires = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
if not expires or expires <= now then
    redis.call('DEL', KEYS[1])
    return -1
end
local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts')) or 0
if attempts >= 5 then
    redis.call('DEL', KEYS[1])
    return -3
end
if stored ~= ARGV[1] then
    attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
    if attempts >= 5 then
        redis.call('DEL', KEYS[1])
        return -3
    end
    return -2
end
local grantExpires = now + 3600000
-- Create before deleting the code: a failed grant write must not consume the code.
local created = redis.call('SET', KEYS[2], tostring(grantExpires), 'PX', 3600000, 'NX')
if not created then return -4 end
redis.call('DEL', KEYS[1])
return grantExpires
