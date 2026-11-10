#!/usr/bin/env bash
# W1 — 생성기 2대(redis-3 · redis-2)에서 각 500→1000→1500 소켓 = 합계 1k→2k→3k. k6 는 base 모드(VU 당 0.67MB).
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; G1=${DRAFT_REDIS_3:?}; G2=${DRAFT_REDIS_2:?}
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }
STAGES=${STAGES:-"60s:500,60s:500,60s:1000,60s:1000,60s:1500,60s:1500"}
lab "[ -f ~/lab/sample.pid ] && kill \$(cat ~/lab/sample.pid) 2>/dev/null; nohup ~/lab/sample.sh W1-dual 420 >/dev/null 2>&1 < /dev/null &"
echo "===== W1 dual stages(each)=$STAGES  $(date +%T)"
for G in $G1 $G2; do
  ssh $O "$J" ubuntu@$G "cd ~/k6 && mkdir -p results/W1 && ulimit -n 65535 && STAGES='$STAGES' HOLD_SECONDS=50 DATA_DIR=. BASE_URL=http://$LAB:8080 nohup k6 run --quiet --compatibility-mode=base --summary-export=results/W1/summary.json sockets.js > results/W1/k6.txt 2>&1 < /dev/null &" &
done; wait
sleep 395
for G in $G1 $G2; do echo "--- generator $G"; ssh $O "$J" ubuntu@$G "cd ~/k6; for i in \$(seq 1 20); do [ -s results/W1/summary.json ] && break; sleep 3; done; python3 - <<'PY'
import json; m=json.load(open('results/W1/summary.json'))['metrics']
print('connected', m.get('stomp_connected',{}).get('count'), 'failed', m.get('stomp_failed',{}).get('count'), 'ws_sessions', m.get('ws_sessions',{}).get('count'))
c=m.get('stomp_connect_ms'); print('connect_ms p50/p95/max', c and (round(c['med']),round(c['p(95)']),round(c['max'])))
PY
tail -2 results/W1/k6.txt | cut -c1-160; sudo dmesg 2>/dev/null | grep -c 'Killed process' | sed 's/^/oom kills: /'"; done
lab "kill \$(cat ~/lab/sample.pid) 2>/dev/null; sleep 1; echo '--- 서버 샘플(ts cpu heapMB hikari_act pend backlog load1 memAvailMB sockets8080) 30초 간격'; awk -F'\t' 'NR>1 && (NR-2)%3==0' ~/lab/samples/W1-dual.tsv; echo '--- 최대'; awk -F'\t' 'NR>1{if(\$9+0>s)s=\$9; if(\$3+0>h)h=\$3; if(\$2+0>c)c=\$2; if(\$8+0<a||a==\"\")a=\$8} END{print \"sockets_max=\"s\" heap_max=\"h\"MB cpu_max=\"c\" memAvail_min=\"a\"MB\"}' ~/lab/samples/W1-dual.tsv; grep -c -i -E 'too many open files|OutOfMemory|RejectedExecution' ~/lab/app.log | sed 's/^/앱 로그 이상: /'"
echo W1DONE
