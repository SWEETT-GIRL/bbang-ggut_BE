#!/usr/bin/env bash
# V1 — VM 자체가 죽는 페일오버. 사람이 콘솔에서 redis-1 EC2 를 Stop/Start 한다(팀 합의 뒤: 캐시·Kafka 도 같이 멈춘다).
# 이 스크립트는 `!` 로 띄워 두면: Lab 앱(sentinel 모드)에 80명 부하를 걸고, redis-1 이 응답을 멈추는 순간부터 승격까지 재고, 유실을 판정한 뒤,
# redis-1 이 돌아올 때까지 기다렸다가 복제본 합류·마스터 복귀까지 확인한다.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; M1=${DRAFT_REDIS_1:?}; M2=${DRAFT_REDIS_2:?}; M3=${DRAFT_REDIS_3:?}
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }; rssh(){ ssh $O "$J" ubuntu@$1 "${@:2}"; }
sent(){ rssh $1 "docker exec sweetgirl-prod-redis-redis-draft-sentinel-1 redis-cli -p 26380 ${*:2}"; }
ms(){ python3 -c 'import time;print(int(time.time()*1000))'; }
up1(){ rssh $M3 "timeout 2 bash -c '</dev/tcp/$M1/6380'" >/dev/null 2>&1; }
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null) && scp $O "$J" -q $S/lab-k6/tokens.csv ubuntu@$GEN:~/k6/tokens.csv
echo "===== V1 준비: Lab 앱 sentinel 모드, 80명 부하 시작  $(date +%T)"
lab "cd ~/lab && ./run-app.sh sentinel none" | tail -1
gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=600s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --console-output=results/v1.console.log --summary-export=results/v1.json loss.js > results/v1.txt 2>&1" & KP=$!
sleep 15; echo ">>> 지금 콘솔에서 ec2-sweetgirl-prod-redis-kafka-1 을 Instance state → Stop 하세요. (최대 10분 기다림)"
for i in $(seq 1 600); do up1 || break; sleep 1; done; t0=$(ms); echo "  redis-1:6380 무응답 감지 $(date +%T)"
for i in $(seq 1 600); do m=$(sent $M2 sentinel get-master-addr-by-name draft 2>/dev/null | head -1); [ -n "$m" ] && [ "$m" != "$M1" ] && break; sleep 0.2; done; echo "  승격 → $m, 감지 뒤 $(( $(ms)-t0 ))ms"
echo "  캐시 마스터도: $(sent $M2 sentinel get-master-addr-by-name cache 2>/dev/null | head -1)"
sleep 60; echo ">>> 이제 콘솔에서 redis-1 을 Start 하세요. (돌아올 때까지 기다림)"
for i in $(seq 1 900); do up1 && break; sleep 1; done; t1=$(ms); echo "  redis-1:6380 응답 재개 $(date +%T)"
for i in $(seq 1 120); do r=$(sent $M2 sentinel replicas draft | grep -A1 '^master-link-status$' | tail -1); [ "$r" = ok ] && break; sleep 2; done; echo "  redis-1 복제본 합류: link=$r, 재개 뒤 $(( $(ms)-t1 ))ms"
kill $KP 2>/dev/null; gen "pkill -x k6" >/dev/null 2>&1; sleep 15
gen "cd ~/k6 && python3 - <<'PY'
import json,re
t=open('results/v1.txt').read(); k=json.loads(t[t.index('{'):]) if '{' in t else {}
print('  k6: 503', k.get('unavailable503'), 'retries', k.get('retries'), 'givenUp', k.get('givenUp'), '첫 성공까지 med', (k.get('promotionMs') or {}).get('med'))
PY" 2>/dev/null || echo "  (k6 요약 없음 — 중단된 실행)"
echo "--- 마스터 redis-1 로 복귀"; for i in 1 2 3 4 5; do m=$(sent $M2 sentinel get-master-addr-by-name draft | head -1); [ "$m" = "$M1" ] && break; sent $M2 sentinel failover draft >/dev/null 2>&1; sleep 12; done; echo "  draft master=$(sent $M2 sentinel get-master-addr-by-name draft | head -1) cache master=$(sent $M2 sentinel get-master-addr-by-name cache | head -1)"
lab "cd ~/lab && ./run-app.sh standalone none" | tail -1
echo V1DONE
