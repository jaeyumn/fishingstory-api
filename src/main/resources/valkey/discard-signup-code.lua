-- A late failure must never remove the replacement code.
if redis.call('HGET', KEYS[1], 'operationId') ~= ARGV[1] then return 0 end
return redis.call('DEL', KEYS[1])
