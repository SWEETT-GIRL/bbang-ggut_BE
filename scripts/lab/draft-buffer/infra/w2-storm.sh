#!/usr/bin/env bash
# W2 — 재연결 폭풍. 소켓 1,000개(redis-3) 유지 중 Lab 앱을 재시작. 지터 0~3초(앱 규칙) vs 0초(대조군). 각 240초.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }
scp $O "$J" -q $S/lab-k6/tokens.csv $S/lab-k6/lib.js $S/lab-k6/storm.js ubuntu@$GEN:~/k6/
for JIT in 3000 0; do
  lab "cd ~/lab && ./run-app.sh standalone none" | tail -1
  lab "[ -f ~/lab/sample.pid ] && kill \$(cat ~/lab/sample.pid) 2>/dev/null; nohup ~/lab/sample.sh W2-jitter$JIT 250 >/dev/null 2>&1 < /dev/null &"
  echo "===== W2 jitter=${JIT}ms, 1000 소켓, 90초 뒤 앱 재시작  $(date +%T)"
  gen "cd ~/k6 && mkdir -p results/W2-$JIT && ulimit -n 65535 && VUS=1000 DURATION=240s JITTER_MS=$JIT DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --compatibility-mode=base --summary-export=results/W2-$JIT/summary.json storm.js > results/W2-$JIT/k6.txt 2>&1" & KPID=$!
  sleep 90; T0=$(python3 -c 'import time;print(int(time.time()*1000))'); echo "--- 앱 재시작 $(date +%T)"; lab "cd ~/lab && ./run-app.sh standalone none" | tail -1; echo "앱 복귀까지 $(( $(python3 -c 'import time;print(int(time.time()*1000))') - T0 ))ms"
  wait $KPID 2>/dev/null; sleep 5
  gen "cd ~/k6; for i in \$(seq 1 20); do [ -s results/W2-$JIT/summary.json ] && break; sleep 3; done; python3 - <<'PY'
import json; m=json.load(open('results/W2-$JIT/summary.json'))['metrics']
print('connected', m.get('stomp_connected',{}).get('count'), 'dropped', m.get('stomp_dropped',{}).get('count'))
r=m.get('stomp_reconnect_ms'); print('reconnect_ms(끊김→재접속) p50/p95/max', r and (round(r['med']),round(r['p(95)']),round(r['max'])), 'n', r and r.get('count'))
PY"
  lab "kill \$(cat ~/lab/sample.pid) 2>/dev/null; sleep 1; echo '--- 서버 샘플(ts cpu heapMB … load1 memAvail sockets8080) 재시작 전후'; awk -F'\t' 'NR>1' ~/lab/samples/W2-jitter$JIT.tsv | awk 'NR>=7 && NR<=20'"
done
echo W2DONE
