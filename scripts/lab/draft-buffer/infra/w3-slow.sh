#!/usr/bin/env bash
# W3 — 느린 소비자/백프레셔. 안 읽는 소켓 50개(코스 0~49 첫 멤버)에 80명 부하로 DRAFT 신호를 퍼붓는다(120초).
# 보는 것: 서버 힙·세션 수 추이, Spring 기본 상한(sendTimeLimit 10s · sendBufferSizeLimit 512KB)이 세션을 끊는지, 정상 구독자(cross-probe)의 지연이 흔들리는지.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; SLOW=${SLOW:-50}
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }
scp $O "$J" -q $S/lab-k6/slow_consumers.py $S/lab-k6/tokens.csv ubuntu@$GEN:~/k6/
lab "cd ~/lab && ./run-app.sh standalone none" | tail -1
lab "[ -f ~/lab/sample.pid ] && kill \$(cat ~/lab/sample.pid) 2>/dev/null; nohup ~/lab/sample.sh W3-slow 170 >/dev/null 2>&1 < /dev/null &"
echo "===== W3 느린 소비자 $SLOW 개 + 80명 부하 120초  $(date +%T)"
gen "cd ~/k6 && python3 slow_consumers.py $LAB:8080 $SLOW 150 tokens.csv courses.csv > results/w3-slow.txt 2>&1" & sleep 8; gen "cat ~/k6/results/w3-slow.txt"
gen "cd ~/k6 && MODE=push DURATION=120s COURSE_INDEX=45 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/w3-healthy.json cross-probe.js > results/w3-healthy.txt 2>&1" & PH=$!; gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=120s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/w3-load.json loss.js >/dev/null 2>&1"; wait $PH 2>/dev/null; sleep 8; gen "cd ~/k6 && python3 - <<'PY'
import json
m=json.load(open('results/w3-load.json'))['metrics']; h=m.get('http_req_duration{expected_response:true}',m['http_req_duration']); print('부하 PUT p95', round(h['p(95)']), 'reqs', m['http_reqs']['count'], '503', m.get('memo_draft_503',{}).get('count'))
c=json.load(open('results/w3-healthy.json'))['metrics'].get('companion_visible_latency_ms'); print('정상 구독자 지연 p50/p95/max', c and (round(c['med']),round(c['p(95)']),round(c['max'])), 'n', c and c.get('count'))
PY"
sleep 20; gen "cat ~/k6/results/w3-slow.txt | tail -1"
lab "kill \$(cat ~/lab/sample.pid) 2>/dev/null; sleep 1; echo '--- 서버 샘플 (ts cpu heapMB … sockets8080) 20초 간격'; awk -F'\t' 'NR>1 && (NR-2)%2==0' ~/lab/samples/W3-slow.tsv | cut -f1,2,3,9; echo '--- 세션 수(draft_socket_sessions) 지금:'; curl -s localhost:8091/actuator/prometheus | grep '^draft_socket_sessions'; echo '--- 앱 로그: 느린 세션 처리 흔적'; grep -c -i -E 'send time limit|buffer size limit|SESSION_NOT_RELIABLE|1008' ~/lab/app.log | sed 's/^/건수: /'; grep -i -m3 -E 'send time limit|buffer size limit|SESSION_NOT_RELIABLE' ~/lab/app.log | cut -c1-200"
echo W3DONE
