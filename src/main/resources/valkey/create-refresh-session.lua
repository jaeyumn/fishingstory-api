-- KEYS: refresh-token hash key, user's session index. ARGV: user ID, session ID, token hash.
if redis.call('EXISTS', KEYS[1]) == 1 then return -1 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local ttl = 2592000000
local expires = now + ttl
redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'sessionId', ARGV[2], 'createdAt', now, 'expiresAt', expires)
redis.call('PEXPIRE', KEYS[1], ttl)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
redis.call('ZADD', KEYS[2], expires, ARGV[3])
redis.call('PEXPIRE', KEYS[2], ttl)
return expires
