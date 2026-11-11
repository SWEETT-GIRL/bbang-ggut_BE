#!/usr/bin/env bash
# Redis 가 죽거나(kill) 멈춘(pause) 동안 PUT 이 어떻게 실패하는가 — 끊긴 동안의 명령을 쌓을 때(기본)와 바로 거절할 때 비교.
# 운영 초안 Redis 마스터 컨테이너만 건드린다(캐시 Redis 는 그대로). 사람이 `!` 로 실행.
#   kill  : docker kill → 15초 뒤 docker start      pause : docker pause → 20초 뒤 docker unpause
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
SEN=${DRAFT_REDIS_3:?}; M1=${DRAFT_REDIS_1:?}; C=sweetgirl-prod-redis-redis-draft-1; SC=sweetgirl-prod-redis-redis-draft-sentinel-1; JAR=api-failfast.jar; VUS=${VUS:-1280}
sent(){ rsh $SEN "docker exec $SC redis-cli -p 26380 $*"; }
master(){ sent sentinel get-master-addr-by-name draft | head -1; }
replica_ok(){ sent sentinel replicas draft | tr '\n' ' ' | grep -q 'master-link-status ok'; }
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
rsh $LAB "[ -f ~/lab/$JAR ] || cp ~/lab/api-0.0.1-SNAPSHOT.jar ~/lab/$JAR"; up $S/$JAR ubuntu@$LAB:lab/$JAR || exit 1
up $S/lab-k6/sample-fo.sh ubuntu@$LAB:lab/ || exit 1
up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/failfast.js $S/lab-k6/drive-failfast.sh ubuntu@$GEN:k6/ || exit 1
rm -f $S/lab-k6/tokens.csv
round(){ # $1 kill|pause  $2 reject(true|false)
  local kind=$1 reject=$2 L=fo-$1-$([ $2 = true ] && echo reject || echo queue) dur=$([ $1 = pause ] && echo 95 || echo 80)
  rsh $LAB "cp ~/lab/$JAR ~/lab/api-0.0.1-SNAPSHO?.jar" || exit 1
  rsh $LAB "cd ~/lab && HEAP_PCT=50 APP_EXTRA='--course.draft.redis.reject-when-disconnected=$reject --server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=400 --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1" || return 1
  local m0; m0=$(master); echo "=========== $L  끊긴 동안 거절=$reject  master=$m0  $(date +%T)"
  rsh $LAB "chmod +x ~/lab/sample-fo.sh; nohup ~/lab/sample-fo.sh $L $((dur + 10)) > /dev/null 2>&1 < /dev/null & echo 표본시작" >/dev/null
  rsh $GEN "chmod +x ~/k6/drive-failfast.sh; nohup ~/k6/drive-failfast.sh $LAB:8080 $L $VUS ${dur}s > /dev/null 2>&1 < /dev/null & echo 부하시작" >/dev/null
  sleep 35
  local t0
  if [ $kind = kill ]; then
    t0=$(rsh $m0 "date +%s%3N; docker kill --signal=SIGKILL $C >/dev/null"); echo "  kill t0=$t0"
    sleep 15; rsh $m0 "docker start $C >/dev/null; echo '  컨테이너 다시 시작'"
  else
    t0=$(rsh $m0 "date +%s%3N; docker pause $C >/dev/null"); echo "  pause t0=$t0"
    sleep 20; rsh $m0 "docker unpause $C >/dev/null; echo '  pause 해제'"
  fi
  for i in $(seq 1 40); do rsh $GEN "test -f ~/k6/results/$L/DONE" 2>/dev/null && break; sleep 5; done
  echo "  새 마스터=$(master)"; rsh $GEN "cat ~/k6/results/$L/out.txt"
  rsh $LAB "T0=$t0 python3 - <<'PY'
import os
t0=int(os.environ['T0']); rows=[l.split('\t') for l in open('/tmp/fo-$L.tsv') if l.strip()]
rows=[(int(r[0]),int(r[1]),int(r[2]),float(r[3]),int(r[4]),float(r[5]),int(r[6])) for r in rows]
print('  초(주입 기준)  바쁜 스레드  200/s  503/s  503 평균응답ms')
first503=last503=None; maxbusy=0
for a,b in zip(rows,rows[1:]):
    s=(b[0]-t0)/1000.0; dt=(b[0]-a[0])/1000.0 or 1; ok=(b[2]-a[2])/dt; un=(b[4]-a[4])/dt; avg=1000*(b[5]-a[5])/max(1,b[4]-a[4])
    if un>0:
        first503=s if first503 is None else first503; last503=s
    if 0<=s<=40: maxbusy=max(maxbusy,b[1])
    if -3<=s<=32: print('  %8.1f %10d %7.0f %6.0f %10.0f'%(s,b[1],ok,un,avg))
print('  503 이 난 구간: %s ~ %s 초, 그동안 바쁜 스레드 최대 %d'%(first503 and round(first503,1), last503 and round(last503,1), maxbusy))
PY
grep 'Redis 쓰기 실패' ~/lab/app.log | grep -o 'root=[A-Za-z.]*[^ ]*' | cut -c1-70 | sort | uniq -c | sort -rn | head -4 | sed 's/^/  실패 원인: /'"
  for i in $(seq 1 40); do replica_ok && break; sleep 3; done; replica_ok && echo "  복제본 합류 확인" || echo "  !! 복제본 합류 확인 못 함"
  sleep 8
}
round kill false; round kill true; round pause false; round pause true
echo "--- 마스터를 redis-1 로 되돌리기"; for i in 1 2 3 4; do [ "$(master)" = "$M1" ] && break; sent sentinel failover draft >/dev/null 2>&1; sleep 12; done; echo "  master=$(master)"
echo FAILFASTDONE
