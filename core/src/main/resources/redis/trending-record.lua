-- 급부상 신호 한 건 기록. 하루 한 번 판정과 집계를 한 번에 해서, 중간에 끊겨 "판정만 남고 신호는 빠진" 상태나 TTL 없는 키를 남기지 않는다.
-- KEYS[1] seen   KEYS[2] 시간 버킷(ZSET)   KEYS[3] 그날 회원(HLL)
-- ARGV[1] storeId  ARGV[2] memberId  ARGV[3] 가중치  ARGV[4] seen TTL(ms)  ARGV[5] 버킷·HLL TTL(ms)
-- 반환: 1 세었다, 0 오늘 이미 센 신호다
if not redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[4]) then
  return 0
end
redis.call('ZINCRBY', KEYS[2], ARGV[3], ARGV[1])
redis.call('PEXPIRE', KEYS[2], ARGV[5])
redis.call('PFADD', KEYS[3], ARGV[2])
redis.call('PEXPIRE', KEYS[3], ARGV[5])
return 1
