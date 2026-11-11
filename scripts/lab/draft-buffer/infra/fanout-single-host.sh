#!/usr/bin/env bash
# 전달 지연 측정(끊기는 회선용). 측정은 발생기에서 혼자 돌고, 여기서는 띄우고 결과만 가져온다.
#   JAR=api-relay.jar LABEL=fanout-relay [STAGES=..] [SOCKETS=12000] [APP_ARGS=..] bash fanout-run2.sh
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
STAGES=${STAGES:-"20s:160,40s:160,20s:320,40s:320,20s:640,40s:640,20s:1280,40s:1280,20s:2500,40s:2500"}; SOCKETS=${SOCKETS:-12000}; PROCS=${PROCS:-3}; L=${LABEL:-fanout}; JAR=${JAR:-api-v2head.jar}
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
# 서버에 있는 jar 를 바탕으로 바뀐 부분만 올린다(158MB 를 끊기는 회선으로 통째로 올리지 않는다)
rsh $LAB "[ -f ~/lab/$JAR ] || cp ~/lab/api-0.0.1-SNAPSHOT.jar ~/lab/$JAR"
up $S/$JAR ubuntu@$LAB:lab/$JAR || exit 1
echo "jar 올림 $(date +%T)"
up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/shed.js $S/lab-k6/fanout_probe.py $S/lab-k6/drive-fanout.sh ubuntu@$GEN:k6/ || exit 1
rm -f $S/lab-k6/tokens.csv
rsh $LAB "cp ~/lab/$JAR ~/lab/api-0.0.1-SNAPSHO?.jar" || exit 1
  rsh $LAB "cd ~/lab && HEAP_PCT=50 APP_EXTRA='${APP_ARGS:-} --server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=${TOMCAT_THREADS:-400} --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1" || exit 1
echo "===== $L jar=$JAR sockets=$SOCKETS(코스당 $((SOCKETS / 1500))) stages=$STAGES  $(date +%T)"
rsh $LAB "pkill vmstat; pkill -f watch-queues; nohup vmstat 10 100 > /tmp/vm-$L.txt 2>&1 < /dev/null & nohup ~/lab/watch-queues.sh $L 40 > /dev/null 2>&1 < /dev/null & echo vmstat"
rsh $GEN "chmod +x ~/k6/drive-fanout.sh; rm -f ~/k6/results/$L/DONE; nohup ~/k6/drive-fanout.sh $LAB:8080 $L '$STAGES' $SOCKETS $PROCS > /dev/null 2>&1 < /dev/null & echo 측정시작"
sleep 60
for i in $(seq 1 60); do rsh $GEN "test -f ~/k6/results/$L/DONE" 2>/dev/null && break; sleep 15; done
rsh $GEN "cat ~/k6/results/$L/out.txt"
rsh $LAB "pkill vmstat; awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/lab cpu사용%(10초마다): /'; echo; curl -s localhost:8091/actuator/prometheus | grep -E '^draft_write_total' | sed -E 's/\\{application=\"Croissant\",?/{/' | tr '\n' ' '; echo; grep -c ' ERROR' ~/lab/app.log | sed 's/^/app ERROR lines: /'"
rsh $LAB "pkill -f watch-queues; echo '--- 20초마다: 실행기 대기 · 신호 칸 · 스레드 그룹 CPU%(스레드 수)'; cat /tmp/queues-$L.txt" | cut -c1-330
echo FANOUTDONE
