-- 임대가 만료된 코스(죽은 플러셔가 들고 있던 것)를 due 로 되돌린다.
-- KEYS[1] draft:inflight   KEYS[2] draft:due
-- ARGV[1] now(ms)
-- 반환: 되돌린 코스 수
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
for _, id in ipairs(ids) do
  redis.call('ZREM', KEYS[1], id)
  redis.call('ZADD', KEYS[2], tonumber(ARGV[1]), id)
end
return #ids
