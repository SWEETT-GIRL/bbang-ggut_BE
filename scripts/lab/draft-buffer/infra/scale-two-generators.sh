#!/usr/bin/env bash
# 수평 확장 — 앱 3대 + 발생기 2대(기존 발생기 + lab-app 한 대). 발생기 한 대가 먼저 차서 4대의 한계를 못 본 것(2026-11-11)을 보완.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
APPS=(${DRAFT_LAB_APP_1:?} ${DRAFT_LAB_APP_2:?} ${DRAFT_LAB_APP_3:?}); G2=${DRAFT_LAB_APP_4:?}; L=${LABEL:-scale-3}
STAGES=${STAGES:-"20s:1250,40s:1250,20s:2000,40s:2000,20s:3000,40s:3000,20s:4000,40s:4000,20s:6000,40s:6000"}
TOTAL=$(echo "$STAGES" | tr ',' '\n' | awk -F'[s:]' '{t+=$1} END{print t}'); OUT=$S/scale-results/$L; mkdir -p $OUT
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/scale.js $S/lab-k6/drive-scale.sh ubuntu@$GEN:k6/ || exit 1
rsh $GEN "mkdir -p ~/k6/bin && cp \$(which k6) ~/k6/bin/k6; pgrep -f 'http.server 809[0]' > /dev/null || (cd ~/k6 && setsid nohup python3 -m http.server 8090 --bind $GEN > /dev/null 2>&1 < /dev/null &); sleep 1; echo 발생기1준비" || exit 1
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$G2 "pkill -f 'api-0.0.1-SNAPSHO[T].jar'; mkdir -p ~/k6/bin && cd ~/k6 && for f in tokens.csv courses.csv lib.js scale.js drive-scale.sh; do curl -s -m 30 -o \$f http://$GEN:8090/\$f; done; curl -s -m 60 -o bin/k6 http://$GEN:8090/bin/k6; chmod +x bin/k6 drive-scale.sh; sudo ln -sf ~/k6/bin/k6 /usr/local/bin/k6; sudo sysctl -q -w net.ipv4.ip_local_port_range='1024 65535'; k6 version | cut -c1-30; wc -l < tokens.csv" || exit 1
rsh $LAB "sudo systemctl start postgresql; pkill -f 'api-0.0.1-SNAPSHO[T].jar'; pkill vmstat; sleep 1; nohup vmstat 10 80 > /tmp/vm-$L.txt 2>&1 < /dev/null & echo 'Lab=DB 전용'" >/dev/null
URLS=""
for h in "${APPS[@]}"; do
  ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$h "pkill -f 'api-0.0.1-SNAPSHO[T].jar'; cd ~/lab && HEAP_PCT=50 APP_EXTRA='--server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=400 --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1 | sed 's/^/$h /'" &
  URLS="${URLS:+$URLS,}http://$h:8080"
done
wait
for h in "${APPS[@]}"; do rsh $h "curl -s -m 3 localhost:8091/actuator/health | grep -q UP && echo '$h UP' || echo '$h !! 안 뜸'; pkill vmstat; nohup vmstat 10 80 > /tmp/vm-$L.txt 2>&1 < /dev/null & true"; done
echo "===== $L 앱 3대 · 발생기 2대(각 stages=$STAGES)  $(date +%T)"
rsh $GEN "nohup ~/k6/drive-scale.sh '$URLS' ${L}a '$STAGES' > /dev/null 2>&1 < /dev/null & echo 발생기1시작" &
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$G2 "cd ~/k6; VU_OFFSET=6000 setsid nohup ./drive-scale.sh '$URLS' ${L}b '$STAGES' > /dev/null 2>&1 < /dev/null & sleep 1; echo 발생기2시작" &
wait
sleep $((TOTAL - 15))
for i in $(seq 1 40); do rsh $GEN "test -f ~/k6/results/${L}a/DONE" 2>/dev/null && rsh $G2 "test -f ~/k6/results/${L}b/DONE" 2>/dev/null && break; sleep 5; done
echo "--- 발생기 1"; rsh $GEN "cat ~/k6/results/${L}a/out.txt" | tee $OUT/write-a.txt
echo "--- 발생기 2"; rsh $G2 "cat ~/k6/results/${L}b/out.txt" | tee $OUT/write-b.txt
python3 - $OUT <<'PY'
import sys,re
def rows(f):
    r={}
    for l in open(f):
        m=re.match(r'\s+(\d+):(\d+)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)',l)
        if m: r[int(m[1])]=(int(m[2]),m[3],int(m[4]),int(m[7]),int(m[8]))
    return r
a=rows(sys.argv[1]+'/write-a.txt'); b=rows(sys.argv[1]+'/write-b.txt')
print('--- 합계  단계  편집자  성공/s  p50(둘 중 큰 값)  p95(둘 중 큰 값)')
for k in sorted(a):
    if k in b: print('          %2d %7d %7d %10d %14d  %s'%(k,a[k][0]+b[k][0],a[k][2]+b[k][2],max(a[k][3],b[k][3]),max(a[k][4],b[k][4]),a[k][1]))
PY
for h in "${APPS[@]}"; do rsh $h "pkill vmstat; awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/앱 $h cpu%: /'; echo; grep -c ' ERROR' ~/lab/app.log | sed 's/^/  ERROR 로그: /'; true"; done | tee $OUT/apps.txt
rsh $LAB "pkill vmstat; awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/DB 서버(Lab) cpu%: /'; echo; true" | tee $OUT/db.txt
echo SCALE3DONE
