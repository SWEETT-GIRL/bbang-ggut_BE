#!/usr/bin/env bash
# 큰 사양 한 대 한계 찾기: Lab(t4g.xlarge 4vCPU/16GB, 앱+DB) ← 생성기 c7g.xlarge. 편집자 램프 320→4,000명(0.5초 간격 PUT).
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_LAB_GEN:?}; M1=${DRAFT_REDIS_1:?}; PW=$(cat $S/redis_pw.txt)
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }
STAGES=${STAGES:-"20s:640,40s:640,20s:1280,40s:1280,20s:2500,40s:2500,20s:4000,40s:4000,20s:6000,40s:6000"}; L=${LABEL:-big-ramp}
scp $O "$J" -q $S/api-final.jar ubuntu@$LAB:~/lab/api-0.0.1-SNAPSHOT.jar
scp $O "$J" -q $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/buffered.js ubuntu@$GEN:~/k6/
lab "grep -q 'APP_EXTRA' ~/lab/run-app.sh || sed -i 's| \$R > ~/lab/app.log| \$R \${APP_EXTRA:-} > ~/lab/app.log|' ~/lab/run-app.sh; cd ~/lab && HEAP_PCT=50 APP_EXTRA='--server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=400 --logging.level.org.springframework.web=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh ${MODE:-sentinel} none | tail -1"
lab "[ -f ~/lab/sample.pid ] && kill \$(cat ~/lab/sample.pid) 2>/dev/null; nohup ~/lab/sample.sh $L 400 >/dev/null 2>&1 < /dev/null &" &
( printf '%s\n' "$PW" | ssh $O "$J" ubuntu@$M1 "read -r PW; for i in \$(seq 1 36); do echo \"\$(date +%T) \$(docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' sweetgirl-prod-redis-redis-draft-1) ops=\$(docker exec -e REDISCLI_AUTH=\$PW sweetgirl-prod-redis-redis-draft-1 redis-cli -p 6380 --no-auth-warning info stats | grep instantaneous_ops | cut -d: -f2 | tr -d '\r')\"; sleep 8; done" > $S/$L.redis.txt 2>&1 ) & RP=$!
echo "===== $L stages=$STAGES  $(date +%T)"
gen "cd ~/k6 && mkdir -p results/$L && ulimit -n 200000 && STAGES='$STAGES' SEARCH_RATE=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --compatibility-mode=base --summary-export=results/$L/summary.json buffered.js > results/$L/k6.txt 2>&1; python3 - <<'PY'
import json
m=json.load(open('results/$L/summary.json'))['metrics']
print('reqs', m['http_reqs']['count'], 'rate/s', round(m['http_reqs']['rate']), 'fail', round(m['http_req_failed']['value'],4))
for k in sorted(m, key=lambda x: (len(x), x)):
    if k.startswith('memo_save_duration{stage') and 'p(95)' in m[k]: print(k.replace('memo_save_duration',''), 'p50', round(m[k]['med']), 'p95', round(m[k]['p(95)']), 'max', round(m[k]['max']))
PY
free -m | awk '/Mem/{print \"gen mem used=\"\$3\"MB\"}'; tail -2 results/$L/k6.txt | cut -c1-160; sudo dmesg | grep -c 'Killed process' | sed 's/^/gen oom: /'"
kill $RP 2>/dev/null; wait $RP 2>/dev/null
lab "kill \$(cat ~/lab/sample.pid) 2>/dev/null; echo '--- lab (ts cpu heapMB hikari_act pend backlog load1 memAvail conns)'; awk -F'\t' 'NR>1 && (NR-2)%2==0' ~/lab/samples/$L.tsv; curl -s localhost:8091/actuator/prometheus | grep -E '^draft_(write_total|flush_batch_size_(count|sum)|inflight_reclaimed)' | awk '{printf \"%s=%s \", \$1, \$2}'; echo; grep -c ' ERROR' ~/lab/app.log | sed 's/^/app ERROR lines: /'"
echo "--- redis-1 (time cpu mem ops/s)"; awk 'NR%2==1' $S/$L.redis.txt | cut -c1-80
echo BIGDONE
