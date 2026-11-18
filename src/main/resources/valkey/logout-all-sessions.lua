-- KEYS: user's session index, then every refresh key in the observed index.
-- ARGV: authenticated user ID, then observed token hashes.
-- Return 0 without writes if login/rotation changed the snapshot, -1 for invalid ownership, 1 on completion.
if redis.call('ZCARD', KEYS[1]) ~= #KEYS - 1 then return 0 end
for i = 2, #KEYS do
    if not redis.call('ZSCORE', KEYS[1], ARGV[i]) then return 0 end
end

-- Validate every existing key before deleting anything because Lua runtime errors do not roll writes back.
for i = 2, #KEYS do
    if redis.call('EXISTS', KEYS[i]) == 1 then
        local owner = redis.call('HGET', KEYS[i], 'userId')
        if owner ~= ARGV[1] then return -1 end
    end
end
for i = 2, #KEYS do redis.call('DEL', KEYS[i]) end
redis.call('DEL', KEYS[1])
return 1
