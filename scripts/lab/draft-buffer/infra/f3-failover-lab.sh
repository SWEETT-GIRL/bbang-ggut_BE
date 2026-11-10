#!/usr/bin/env bash
# F3 — Lab 에서 페일오버 체감 시간 전·후 비교. 앱(Lab, sentinel 모드) + k6(redis-3) 80명 60초, 40초에 현재 마스터 컨테이너 docker kill.
#   before = readFrom(MASTER) 있는 jar ×2, after = readFrom 뺀 jar ×3. 사람이 `!` 로 실행(docker kill).
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; M1=${DRAFT_REDIS_1:?}; M2=${DRAFT_REDIS_2:?}; C=sweetgirl-prod-redis-redis-draft-1; PW=$(cat $S/redis_pw.txt)
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }; rssh(){ ssh $O "$J" ubuntu@$1 "${@:2}"; }
sent(){ rssh $M2 "docker exec sweetgirl-prod-redis-redis-draft-sentinel-1 redis-cli -p 26380 $*" 2>/dev/null || rssh $M1 "docker exec sweetgirl-prod-redis-redis-draft-sentinel-1 redis-cli -p 26380 $*"; }
master(){ sent sentinel get-master-addr-by-name draft | head -1; }
rc(){ printf '%s\n' "$PW" | ssh $O "$J" ubuntu@$1 "read -r PW; docker exec -e REDISCLI_AUTH=\$PW $C redis-cli -p 6380 --no-auth-warning ${*:2}"; }
ms(){ python3 -c 'import time;print(int(time.time()*1000))'; }
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null) && scp $O "$J" -q $S/lab-k6/tokens.csv ubuntu@$GEN:~/k6/tokens.csv
scp $O "$J" -q $S/api-before-f3.jar ubuntu@$LAB:~/lab/api-before-f3.jar; scp $O "$J" -q $S/api-after-f3.jar ubuntu@$LAB:~/lab/api-after-f3.jar
round(){ # $1 라벨
  m0=$(master); echo "=== $1  master=$m0  $(date +%T)"
  gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=60s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --console-output=results/$1.console.log --summary-export=results/$1.json loss.js > results/$1.txt 2>&1" & KP=$!
  sleep 40; t0=$(ms); rssh $m0 "docker kill --signal=SIGKILL $C >/dev/null"; tk=$(ms)
  for i in $(seq 1 100); do m1=$(master); [ -n "$m1" ] && [ "$m1" != "$m0" ] && break; sleep 0.2; done; echo "  kill_cmd_ms=$((tk-t0)) 승격확인_ms=$(( $(ms)-t0 )) new=$m1"
  wait $KP 2>/dev/null
  gen "cd ~/k6 && python3 -c \"import json; t=open('results/$1.txt').read(); k=json.loads(t[t.index('{'):]); p=k.get('promotionMs') or {}; print('  체감(첫 실패→첫 성공) med', round(p.get('med',0)), 'max', round(p.get('max',0)), '| 503', k.get('unavailable503'), 'givenUp', k.get('givenUp'))\""
  lab "grep 'Redis 쓰기 실패' ~/lab/app.log | grep -o 'root=[A-Za-z.]*' | sort | uniq -c | tr '\n' ' '; echo; echo \"  503 구간: \$(grep 'Redis 쓰기 실패' ~/lab/app.log | head -1 | cut -c12-23) ~ \$(grep 'Redis 쓰기 실패' ~/lab/app.log | tail -1 | cut -c12-23)\"; grep -E 'Reconnected to' ~/lab/app.log | tail -2 | cut -c12-23,60-130"
  rssh $m0 "docker start $C >/dev/null"; for i in $(seq 1 40); do rc $m1 info replication 2>/dev/null | grep -q 'connected_slaves:1' && break; sleep 1; done; sleep 10
}
for V in before after; do
  lab "cp ~/lab/api-$V-f3.jar ~/lab/api-0.0.1-SNAPSHOT.jar"
  N=$([ $V = before ] && echo 2 || echo 3)
  for i in $(seq 1 $N); do lab "cd ~/lab && ./run-app.sh sentinel none" | tail -1; round f3-$V-$i; done
done
echo "--- 마스터 redis-1 복귀"; for i in 1 2 3 4; do [ "$(master)" = "$M1" ] && break; sent sentinel failover draft >/dev/null 2>&1; sleep 12; done; echo "  master=$(master)"
rc $M1 -n 3 flushdb >/dev/null; lab "cd ~/lab && ./run-app.sh standalone none" | tail -1
echo F3DONE
