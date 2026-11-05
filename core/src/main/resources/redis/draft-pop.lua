-- 반영 시각이 된 코스를 꺼내 임대(inflight) 로 옮긴다. 꺼내는 순간 firstDirtyAt 을 지워 다음 묶음의 10초 상한이 새로 시작된다.
-- KEYS[1] draft:due   KEYS[2] draft:inflight
-- ARGV[1] now(ms)  ARGV[2] lease(ms)  ARGV[3] limit  ARGV[4] metaPrefix(draft:meta:)
-- 반환: courseId 목록
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[3]))
local leaseUntil = tonumber(ARGV[1]) + tonumber(ARGV[2])
for _, id in ipairs(ids) do
  redis.call('ZREM', KEYS[1], id)
  redis.call('ZADD', KEYS[2], leaseUntil, id)
  redis.call('DEL', ARGV[4] .. id)
end
return ids
