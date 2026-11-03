-- 반영을 끝낸 코스를 놓는다. 반영한 seq 이하인 필드만 지우고(반영 중 들어온 새 글은 남는다), 임대를 풀고,
-- 남은 초안이 있으면 now + redueDelay 에 다시 반영하도록 due 에 올린다. 필드 쌍이 없으면 "실패 — 전부 남기고 재시도" 다.
-- KEYS[1] draft:{courseId}   KEYS[2] draft:meta:{courseId}   KEYS[3] draft:due   KEYS[4] draft:inflight
-- ARGV[1] courseId  ARGV[2] now(ms)  ARGV[3] redueDelay(ms)  ARGV[4..] field, flushedSeq 쌍
-- 반환: 남은 초안 수
local i = 4
while i < #ARGV do
  local field = ARGV[i]
  local flushedSeq = tonumber(ARGV[i + 1])
  local current = redis.call('HGET', KEYS[1], field)
  if current then
    local separator = string.find(current, ':', 1, true)
    local currentSeq = tonumber(string.sub(current, 1, separator - 1))
    if currentSeq <= flushedSeq then
      redis.call('HDEL', KEYS[1], field)
    end
  end
  i = i + 2
end
redis.call('ZREM', KEYS[4], ARGV[1])

local remaining = redis.call('HLEN', KEYS[1])
if remaining == 0 then
  redis.call('DEL', KEYS[2])
  return 0
end
if not redis.call('HGET', KEYS[2], 'firstDirtyAt') then
  redis.call('HSET', KEYS[2], 'firstDirtyAt', ARGV[2])
end
redis.call('ZADD', KEYS[3], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
return remaining
