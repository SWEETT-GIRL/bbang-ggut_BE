#!/usr/bin/env bash
# W1 — 소켓 한계. Lab 앱(ulimit 65535) 에 소켓 1k→4k 램프. 서버 샘플러 10초, k6 는 redis-3.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }
scp $O "$J" -q $S/lab-k6/tokens.csv ubuntu@$GEN:~/k6/tokens.csv
lab "grep -q 'ulimit -n' ~/lab/run-app.sh || sed -i '2i ulimit -n 65535' ~/lab/run-app.sh; cd ~/lab && ./run-app.sh standalone none | tail -1; ulimit -n; cat /proc/\$(cat ~/lab/app.pid)/limits | grep 'open files'"
STAGES=${STAGES:-"60s:1000,60s:1000,60s:2000,60s:2000,60s:3000,60s:3000,60s:4000,60s:4000"}
lab "[ -f ~/lab/sample.pid ] && kill \$(cat ~/lab/sample.pid) 2>/dev/null; nohup ~/lab/sample.sh W1-sockets 560 >/dev/null 2>&1 &"
echo "===== W1 stages=$STAGES  $(date +%T)"
gen "cd ~/k6 && mkdir -p results/W1 && ulimit -n 65535 && STAGES='$STAGES' HOLD_SECONDS=50 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/W1/summary.json sockets.js > results/W1/k6.txt 2>&1; python3 - <<'PY'
import json; m=json.load(open('results/W1/summary.json'))['metrics']
for k in ('stomp_connected','stomp_failed','ws_sessions','ws_connecting'):
    v=m.get(k); print(k, v and (v.get('count') or v.get('value') or {k2:round(v[k2]) for k2 in v if k2 in ('avg','p(95)','max')}))
c=m.get('stomp_connect_ms'); print('connect_ms p50/p95/max', c and (round(c['med']),round(c['p(95)']),round(c['max'])))
PY"
lab "kill \$(cat ~/lab/sample.pid) 2>/dev/null; sleep 1; echo '--- 서버 샘플(ts cpu heapMB hikari_act pend backlog load1 memAvailMB sockets8080)'; awk -F'\t' 'NR>1 && NR%3==0' ~/lab/samples/W1-sockets.tsv | head -24; grep -c -i -E 'too many open files|OutOfMemory' ~/lab/app.log | sed 's/^/앱 로그 open-files\/OOM: /'"
echo W1DONE
