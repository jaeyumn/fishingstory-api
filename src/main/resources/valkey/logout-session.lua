-- KEYS: user's session index, then every refresh key in the observed index.
-- ARGV: authenticated user ID, authenticated session ID, then observed token hashes.
-- Return 0 without writes if login/rotation changed the snapshot; 1 means session absent.
if redis.call('ZCARD', KEYS[1]) ~= #KEYS - 1 then return 0 end
for i = 2, #KEYS do
    if not redis.call('ZSCORE', KEYS[1], ARGV[i + 1]) then return 0 end
end

-- Read all types/owners before writing: a Lua runtime error cannot roll back writes.
local targets = {}
local missing = {}
for i = 2, #KEYS do
    local values = redis.call('HMGET', KEYS[i], 'userId', 'sessionId')
    if values[1] == ARGV[1] and values[2] == ARGV[2] then
        targets[#targets + 1] = i
    elseif redis.call('EXISTS', KEYS[i]) == 0 then
        missing[#missing + 1] = i
    end
end
for _, i in ipairs(targets) do
    redis.call('DEL', KEYS[i])
    redis.call('ZREM', KEYS[1], ARGV[i + 1])
end
for _, i in ipairs(missing) do
    redis.call('ZREM', KEYS[1], ARGV[i + 1])
end
return 1
