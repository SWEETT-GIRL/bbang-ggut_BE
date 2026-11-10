#!/usr/bin/env bash
# R2 — 영속성 정책. appendfsync everysec(현재) vs always: (1) PUT p95 (Lab 80명 60초) (2) 부하 중 호스트 kill -9(감시자 정지 상태) 뒤 확인된 글 유실. 끝나면 everysec 복원.
# 사람이 `!` 로 실행(CONFIG SET·kill 은 Claude 권한에서 막힘).
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; M1=${DRAFT_REDIS_1:?}; M2=${DRAFT_REDIS_2:?}; M3=${DRAFT_REDIS_3:?}; PW=$(cat $S/redis_pw.txt); C=sweetgirl-prod-redis-redis-draft-1; SC=sweetgirl-prod-redis-redis-draft-sentinel-1
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }; rssh(){ ssh $O "$J" ubuntu@$1 "${@:2}"; }
m1(){ printf '%s\n' "$PW" | ssh $O "$J" ubuntu@$M1 "read -r PW; D=\"docker exec -e REDISCLI_AUTH=\$PW $C redis-cli -p 6380 --no-auth-warning\"; $1"; }
ms(){ python3 -c 'import time;print(int(time.time()*1000))'; }
verify(){ # $1 라벨: 생성기 콘솔 로그 vs Lab DB (마지막 글·확인된 글 유실)
  scp $O "$J" -q "ubuntu@$GEN:~/k6/results/$1.console.log" /tmp/c.log && scp $O "$J" -q /tmp/c.log ubuntu@$LAB:/tmp/c.log
  lab 'export PGPASSWORD=lab; python3 - <<"PY"
import re,subprocess,json
last={}
for line in open("/tmp/c.log",errors="ignore"):
    m=re.search(r"R,(\d+):(\d+):(\d+),(\d+),(\d+)",line)
    if not m: continue
    k=(m[1],m[2],m[3]); seq,acc=int(m[4]),int(m[5]); r=last.setdefault(k,[0,0]); r[0]=max(r[0],seq); r[1]=max(r[1],acc)
q="SELECT cs.course_id,cs.store_id,csm.member_id,csm.seq FROM course_store_memos csm JOIN course_stores cs ON cs.id=csm.course_store_id JOIN courses c ON c.id=cs.course_id WHERE c.title LIKE \x27[초안]코스%\x27 AND csm.deleted_at IS NULL"
rows=subprocess.check_output(["psql","host=127.0.0.1 port=5432 dbname=soboro user=sweetgirl","-At","-F,","-c",q],text=True)
db={tuple(l.split(",")[:3]):int(l.split(",")[3]) for l in rows.splitlines()}
print(json.dumps(dict(cells=len(last), lost_final_text=sum(1 for k,(s,a) in last.items() if db.get(k,0)<s), lost_acked=sum(1 for k,(s,a) in last.items() if a and db.get(k,0)<a))))
PY'
}
crash_round(){ # $1 라벨 — 부하 60초, 40초에 호스트 kill -9(자동 재시작), 끝나고 drain·검증
  gen "cd ~/k6 && (PROMISE=on VUS=80 DURATION=60s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --console-output=results/$1.console.log --summary-export=results/$1.json loss.js > results/$1.txt 2>&1 &)"
  sleep 40; t0=$(ms); rssh $M1 "sudo kill -9 \$(docker inspect -f '{{.State.Pid}}' $C)"; for i in $(seq 1 300); do m1 '$D ping' 2>/dev/null | grep -q PONG && break; sleep 0.2; done; echo "  redis 복귀 $(( $(ms)-t0 ))ms"
  sleep 35; for i in $(seq 1 60); do d=$(m1 '$D -n 3 zcard draft:due'); h=$(m1 '$D -n 3 --scan --pattern "draft:{*" | wc -l'); [ "$d" = 0 ] && [ "$h" -eq 0 ] && break; sleep 1; done
  gen "cd ~/k6 && python3 -c \"import json; m=json.load(open('results/$1.json'))['metrics']; h=m.get('http_req_duration{expected_response:true}',m['http_req_duration']); print('  PUT p50', round(h['med']), 'p95', round(h['p(95)']), '503', m.get('memo_draft_503',{}).get('count'))\""; echo -n "  유실: "; verify $1
}
echo "===== 준비: Lab 앱, 감시자 3개 정지(크래시 때 승격 막기)  $(date +%T)"
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null) && scp $O "$J" -q $S/lab-k6/tokens.csv ubuntu@$GEN:~/k6/tokens.csv
lab "cd ~/lab && ./run-app.sh standalone none" | tail -1
for h in $M1 $M2 $M3; do rssh $h "docker stop -t 2 $SC >/dev/null" && echo "  $h sentinel stopped"; done
for FS in always everysec; do
  echo "===== appendfsync=$FS  $(date +%T)"; m1 "\$D config set appendfsync $FS && \$D config get appendfsync | tail -1"
  echo "--- (0) 워밍업 30초(버림)"; gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=30s SETTLE_SECONDS=0 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet loss.js >/dev/null 2>&1"
  echo "--- (1) 부하 60초 p95"; gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=60s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/r2b-$FS-load.json loss.js >/dev/null 2>&1; python3 -c \"import json; m=json.load(open('results/r2b-$FS-load.json'))['metrics']; h=m.get('http_req_duration{expected_response:true}',m['http_req_duration']); print('  PUT p50', round(h['med']), 'p95', round(h['p(95)']), 'reqs', m['http_reqs']['count'])\""
  echo "--- (2) 부하 중 kill -9 → 유실"; crash_round r2b-$FS-crash
done
echo "===== 복원: appendfsync everysec, 감시자 재시작  $(date +%T)"; m1 '$D config set appendfsync everysec >/dev/null; $D config get appendfsync | tail -1'
for h in $M1 $M2 $M3; do rssh $h "docker start $SC >/dev/null" && echo "  $h sentinel started"; done; sleep 8
m1 '$D -n 3 flushdb >/dev/null; echo "Lab 초안(DB3) 비움"'; env -u REDISCLI_AUTH redis-cli --no-auth-warning -h 127.0.0.1 -p 1 ping >/dev/null 2>&1 || true
rssh $M1 "docker exec $SC redis-cli -p 26380 sentinel ckquorum draft; docker exec $SC redis-cli -p 26380 sentinel get-master-addr-by-name draft | head -1"
echo R2DONE
