local values = redis.call('HMGET', KEYS[1], 'userId', 'sessionId', 'expiresAt')
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expires = tonumber(values[3])
if not values[1] or not values[2] or not expires or expires <= now then return nil end
return values[1] .. ':' .. values[2]
