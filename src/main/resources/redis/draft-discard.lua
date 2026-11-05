-- PATCH 가 같은 칸을 DB 에 바로 반영한 뒤 — 그보다 새롭지 않은 초안은 지운다. due·inflight 는 건드리지 않는다
-- (빈 Hash 가 due 에 남아 있어도 플러셔가 꺼내 보고 그냥 놓는다).
-- KEYS[1] draft:{courseId}
-- ARGV[1] field  ARGV[2] seq
-- 반환: 지웠으면 1
local current = redis.call('HGET', KEYS[1], ARGV[1])
if not current then
  return 0
end
local separator = string.find(current, ':', 1, true)
local currentSeq = tonumber(string.sub(current, 1, separator - 1))
if currentSeq <= tonumber(ARGV[2]) then
  return redis.call('HDEL', KEYS[1], ARGV[1])
end
return 0
