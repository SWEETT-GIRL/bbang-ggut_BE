#!/usr/bin/env bash
# 2번 — 앱 2대(A:8080, B:8082, 같은 Lab 서버) 검증: (a) 부하를 반반 나눠 유실·중복 (b) A 에 쓰고 B 소켓으로 받기 (c) 회원당 초당 제한이 2대에서 2배 되는지
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; RB=${DRAFT_REDIS_2:?}; A=http://$LAB:8080; B=http://$RB:8080
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }; rb(){ ssh $O "$J" ubuntu@$RB "$@"; }
scp $O "$J" -q $S/lab-k6/tokens.csv $S/lab-k6/lib.js $S/lab-k6/ratelimit.js $S/lab-k6/cross-probe.js ubuntu@$GEN:~/k6/
m(){ $1 "curl -s localhost:8091/actuator/prometheus | grep -E '^draft_(write_total|flush_total|inflight_reclaimed_total|flush_batch_size_sum)' | awk '{printf \"%s=%s \", \$1, \$2}'"; }
echo "===== 앱 2대 기동 (A=Lab, B=redis-2)  $(date +%T)"; lab "cd ~/lab && ./run-app.sh standalone none" | tail -1; rb "cd ~/lab && ./run-app-B.sh standalone" | tail -1
echo "===== (a) 부하 반반 80명×60초 (A 40 / B 40) + (b) A→B 신호 지연 60초  $(date +%T)"
gen "cd ~/k6 && mkdir -p results/two"; gen "cd ~/k6 && PROMISE=on VUS=40 DURATION=60s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=$A k6 run --quiet --console-output=results/two/consoleA.log --summary-export=results/two/a.json loss.js > results/two/kA.txt 2>&1" & PA=$!; gen "cd ~/k6 && PROMISE=on VUS=40 DURATION=60s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=$B VU_OFFSET=40 k6 run --quiet --console-output=results/two/consoleB.log --summary-export=results/two/b.json loss.js > results/two/kB.txt 2>&1" & PB=$!; sleep 2; gen "cd ~/k6 && MODE=push DURATION=60s COURSE_INDEX=45 WRITER_URL=$A READER_URL=$B DATA_DIR=. BASE_URL=$A k6 run --quiet --summary-export=results/two/cross.json cross-probe.js > results/two/cross.txt 2>&1"; wait $PA $PB 2>/dev/null; sleep 20; gen "cd ~/k6 && python3 - <<'PY'
import json
for f in ('a','b'):
    m=json.load(open(f'results/two/{f}.json'))['metrics']; h=m.get('http_req_duration{expected_response:true}',m['http_req_duration'])
    print(f, 'reqs', m['http_reqs']['count'], 'fail', round(m['http_req_failed']['value'],4), 'p95', round(h['p(95)']))
c=json.load(open('results/two/cross.json'))['metrics'].get('companion_visible_latency_ms'); print('cross A→B latency p50/p95/max', c and (round(c['med']),round(c['p(95)']),round(c['max'])), 'n', c and c.get('count'))
PY"
echo "--- 앱 지표 A / B"; echo "A: $(m lab)"; echo "B: $(m rb)"
echo "--- 유실·중복 판정(Lab DB): 두 콘솔 로그의 마지막 seq vs DB"
scp $O "$J" -q "ubuntu@$GEN:~/k6/results/two/consoleA.log" "ubuntu@$GEN:~/k6/results/two/consoleB.log" /tmp/ && cat /tmp/consoleA.log /tmp/consoleB.log > /tmp/consoleAB.log && scp $O "$J" -q /tmp/consoleAB.log ubuntu@$LAB:/tmp/consoleAB.log
lab 'export PGPASSWORD=lab; python3 - <<"PY"
import re,subprocess,json
last={}
for line in open("/tmp/consoleAB.log",errors="ignore"):
    m=re.search(r"R,(\d+):(\d+):(\d+),(\d+),(\d+)",line)
    if not m: continue
    k=(m[1],m[2],m[3]); seq,acc=int(m[4]),int(m[5]); r=last.setdefault(k,[0,0]); r[0]=max(r[0],seq); r[1]=max(r[1],acc)
q="SELECT cs.course_id,cs.store_id,csm.member_id,csm.seq FROM course_store_memos csm JOIN course_stores cs ON cs.id=csm.course_store_id JOIN courses c ON c.id=cs.course_id WHERE c.title LIKE \x27[초안]코스%\x27 AND csm.deleted_at IS NULL"
rows=subprocess.check_output(["psql","host=127.0.0.1 port=5432 dbname=soboro user=sweetgirl","-At","-F,","-c",q],text=True)
db={tuple(l.split(",")[:3]):int(l.split(",")[3]) for l in rows.splitlines()}
print(json.dumps(dict(cells=len(last), lost_final_text=sum(1 for k,(s,a) in last.items() if db.get(k,0)<s), lost_acked=sum(1 for k,(s,a) in last.items() if a and db.get(k,0)<a), db_ahead_of_sent=sum(1 for k,(s,a) in last.items() if db.get(k,0)>s))))
PY'
echo "===== (c) 회원 1명이 두 서버에 20회/초 20초  $(date +%T)"
gen "cd ~/k6 && URLS=$A,$B RATE=20 DURATION=20s DATA_DIR=. BASE_URL=$A k6 run --quiet --summary-export=results/two/rl.json ratelimit.js > results/two/rl.txt 2>&1; python3 -c \"import json; m=json.load(open('results/two/rl.json'))['metrics']; print('2대 분산:', {k: m[k]['count'] for k in ('rl_ok','rl_429','rl_other') if k in m})\""
gen "cd ~/k6 && URLS=$A RATE=20 DURATION=20s DATA_DIR=. BASE_URL=$A k6 run --quiet --summary-export=results/two/rl1.json ratelimit.js > results/two/rl1.txt 2>&1; python3 -c \"import json; m=json.load(open('results/two/rl1.json'))['metrics']; print('1대만:', {k: m[k]['count'] for k in ('rl_ok','rl_429','rl_other') if k in m})\""
rb "pkill -f api-0.0.1-SNAPSHOT.jar; echo B stopped"
echo TWODONE
