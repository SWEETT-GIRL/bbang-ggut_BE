#!/usr/bin/env bash
# 수평 확장 — 앱 서버 N대(같은 사양), DB 는 Lab 한 대 · 초안 Redis 는 운영 3대를 같이 쓴다. 로드밸런서 없이 k6 가 VU 를 대수로 나눈다.
#   N=1|2|4 [JAR_URL=..] [STAGES=..] [LABEL=scale-N] bash scale.sh
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
ALL=(${DRAFT_LAB_APP_1:?} ${DRAFT_LAB_APP_2:?} ${DRAFT_LAB_APP_3:?} ${DRAFT_LAB_APP_4:?}); N=${N:-1}; APPS=("${ALL[@]:0:$N}"); L=${LABEL:-scale-$N}
STAGES=${STAGES:-"20s:1280,40s:1280,20s:2500,40s:2500,20s:4000,40s:4000,20s:6000,40s:6000,20s:8000,40s:8000"}
TOTAL=$(echo "$STAGES" | tr ',' '\n' | awk -F'[s:]' '{t+=$1} END{print t}'); OUT=$S/scale-results/$L; mkdir -p $OUT
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/scale.js $S/lab-k6/drive-scale.sh ubuntu@$GEN:k6/ || exit 1
# Lab 은 DB 만 — 앱을 내린다. 남은 앱 서버(이번에 안 쓰는 것)도 내린다
rsh $LAB "sudo systemctl start postgresql; pkill -f 'api-0.0.1-SNAPSHO[T].jar'; pkill vmstat; sleep 1; nohup vmstat 10 80 > /tmp/vm-$L.txt 2>&1 < /dev/null & echo 'Lab=DB 전용'" >/dev/null
for h in "${ALL[@]}"; do rsh $h "pkill -f 'api-0.0.1-SNAPSHO[T].jar'; true" >/dev/null 2>&1; done
URLS=""
for h in "${APPS[@]}"; do
  ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$h "cd ~/lab && HEAP_PCT=50 APP_EXTRA='${APP_ARGS:-} --server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=${TOMCAT_THREADS:-400} --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1 | sed 's/^/$h /'" &
  URLS="${URLS:+$URLS,}http://$h:8080"
done
wait
for h in "${APPS[@]}"; do rsh $h "curl -s -m 3 localhost:8091/actuator/health | grep -q UP && echo '$h UP' || echo '$h !! 안 뜸'; pkill vmstat; nohup vmstat 10 80 > /tmp/vm-$L.txt 2>&1 < /dev/null & true"; done
echo "===== $L 앱 ${N}대 stages=$STAGES  $(date +%T)"
rsh $GEN "chmod +x ~/k6/drive-scale.sh; nohup ~/k6/drive-scale.sh '$URLS' $L '$STAGES' > /dev/null 2>&1 < /dev/null & echo 부하시작"
sleep $((TOTAL - 15))
for i in $(seq 1 40); do rsh $GEN "test -f ~/k6/results/$L/DONE" 2>/dev/null && break; sleep 5; done
rsh $GEN "cat ~/k6/results/$L/out.txt" | tee $OUT/write.txt
for h in "${APPS[@]}"; do rsh $h "pkill vmstat; awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/앱 $h cpu%: /'; echo; curl -s localhost:8091/actuator/prometheus | grep -E '^hikaricp_connections_(pending|active)|^draft_write_total' | sed -E 's/\\{[^}]*result=\"([a-z]+)\"\\}/_\\1/; s/\\{[^}]*\\}//' | tr '\n' ' '; echo; grep -c ' ERROR' ~/lab/app.log | sed 's/^/  ERROR 로그: /'"; done | tee $OUT/apps.txt
rsh $LAB "pkill vmstat; awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/DB 서버(Lab) cpu%: /'; echo; PGPASSWORD=lab psql -h 127.0.0.1 -U sweetgirl soboro -Atc \"select 'DB 커넥션 수 '||count(*) from pg_stat_activity where datname='soboro'\"" | tee $OUT/db.txt
echo SCALEDONE
