-- KEYS: old refresh hash key, new refresh hash key, user's session index.
-- ARGV: expected user ID, expected session ID, old token hash, new token hash.
local values = redis.call('HMGET', KEYS[1], 'userId', 'sessionId', 'createdAt', 'expiresAt')
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expires = tonumber(values[4])
if values[1] ~= ARGV[1] or values[2] ~= ARGV[2] or not values[3] or not expires or expires <= now then return 0 end
if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
-- Detect an invalid index type before writing; Lua errors do not roll back writes.
redis.call('ZSCORE', KEYS[3], ARGV[3])
local ttl = 2592000000
local nextExpiry = now + ttl
redis.call('HSET', KEYS[2], 'userId', values[1], 'sessionId', values[2], 'createdAt', values[3], 'expiresAt', nextExpiry)
redis.call('PEXPIRE', KEYS[2], ttl)
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
redis.call('ZADD', KEYS[3], nextExpiry, ARGV[4])
redis.call('PEXPIRE', KEYS[3], ttl)
redis.call('ZREM', KEYS[3], ARGV[3])
redis.call('DEL', KEYS[1])
return nextExpiry
