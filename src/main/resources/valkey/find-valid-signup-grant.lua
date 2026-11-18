-- KEYS: email-bound grant key. Results: original expiry timestamp, -1 missing/expired/invalid.
local stored = redis.call('GET', KEYS[1])
if not stored then return -1 end
local expires = tonumber(stored)
if not expires then return -1 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if expires <= now then return -1 end
return expires
