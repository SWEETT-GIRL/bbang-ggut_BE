-- 초안 쓰기. 저장된 seq 보다 큰 글만 받는다.
-- KEYS[1] draft:{courseId}   KEYS[2] draft:meta:{courseId}   KEYS[3] draft:due   KEYS[4] draft:inflight
-- ARGV[1] field(storeId:memberId)  ARGV[2] seq  ARGV[3] now(ms)  ARGV[4] content
-- ARGV[5] quietWindow(ms)  ARGV[6] maxWait(ms)  ARGV[7] ttl(ms)  ARGV[8] courseId
-- 반환: 받아들여진 seq. 옛 글이면 지금 저장돼 있는 seq
local seq = tonumber(ARGV[2])
local current = redis.call('HGET', KEYS[1], ARGV[1])
if current then
  local separator = string.find(current, ':', 1, true)
  local currentSeq = tonumber(string.sub(current, 1, separator - 1))
  if seq <= currentSeq then
    return currentSeq
  end
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2] .. ':' .. ARGV[3] .. ':' .. ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[7])

local now = tonumber(ARGV[3])
local firstDirtyAt = redis.call('HGET', KEYS[2], 'firstDirtyAt')
if not firstDirtyAt then
  firstDirtyAt = ARGV[3]
  redis.call('HSET', KEYS[2], 'firstDirtyAt', firstDirtyAt)
end
redis.call('PEXPIRE', KEYS[2], ARGV[7])

-- 플러셔가 이미 꺼내 든 코스면 due 에 다시 넣지 않는다. 반영 뒤 release 가 남은 초안을 보고 재등록한다.
if redis.call('ZSCORE', KEYS[4], ARGV[8]) then
  return seq
end
local due = math.min(now + tonumber(ARGV[5]), tonumber(firstDirtyAt) + tonumber(ARGV[6]))
redis.call('ZADD', KEYS[3], due, ARGV[8])
return seq
