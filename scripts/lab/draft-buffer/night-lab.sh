#!/usr/bin/env bash
# 앱 서버(Lab)가 혼자 지휘하는 측정 묶음 — 노트북 없이 돈다. 발생기와는 SSH 가 막혀 있어(보안그룹) HTTP 로 주고받는다:
#   Lab 이 ~/dist/job.txt 에 할 일을 쓰면 발생기(night-gen.sh)가 읽어 부하를 걸고, 끝나면 발생기의 results/<라벨>/DONE 을 Lab 이 본다.
# 진행: ~/lab/night.log   결과: ~/lab/night-results/ 와 발생기 ~/k6/results/<라벨>/   끝: ~/lab/NIGHT_DONE
cd ~/lab; exec >> night.log 2>&1; mkdir -p ~/dist night-results; : > ~/dist/job.txt; [ -f NIGHT_DONE ] && mv NIGHT_DONE NIGHT_DONE.old
GEN=${DRAFT_LAB_GEN:?}
pgrep -f 'http.server 809[0]' > /dev/null || (cd ~/dist && nohup python3 -m http.server 8090 --bind ${DRAFT_LAB_APP:?} > /dev/null 2>&1 < /dev/null &)
COMMON='--server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false'
startapp(){ sudo systemctl start postgresql; cp ~/lab/$1 ~/lab/api-0.0.1-SNAPSHOT.jar; HEAP_PCT=50 APP_EXTRA="--server.tomcat.threads.max=$2 $COMMON" ./run-app.sh sentinel none | tail -1; }
job(){ echo "$(date +%s%N) $*" > ~/dist/job.tmp; mv ~/dist/job.tmp ~/dist/job.txt; }
waitdone(){ for i in $(seq 1 $2); do [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://$GEN:8090/results/$1/DONE)" = 200 ] && return 0; sleep 5; done; echo "!! $1 시간 초과"; return 1; }
fan(){ # <라벨> <jar> <스레드>
  echo "=== fan $1 jar=$2 threads=$3 $(date +%T)"; startapp $2 $3
  pkill -f 'watch-queue[s]'; pkill vmstat
  nohup ~/lab/watch-queues.sh $1 40 > /dev/null 2>&1 < /dev/null &
  nohup vmstat 10 60 > night-results/$1-vmstat.txt 2>&1 < /dev/null &
  job fan $1; waitdone $1 150
  pkill -f 'watch-queue[s]'; cp /tmp/queues-$1.txt night-results/$1-queues.txt
  echo "=== fan $1 끝 $(date +%T)"
}
db(){ # <라벨> <jar> <스레드> <편집자> <정지 초>
  echo "=== db $1 jar=$2 threads=$3 vus=$4 down=$5 $(date +%T)"; startapp $2 $3
  nohup ~/lab/sample-db.sh $1 $((40 + $5 + 190)) > /dev/null 2>&1 < /dev/null &
  job loss $1 $4 $((40 + $5 + 60))
  sleep 42; T0=$(date +%s%3N); sudo systemctl stop postgresql; echo "  DB 정지 $T0"
  sleep $5; sudo systemctl start postgresql; T1=$(date +%s%3N); echo "  DB 시작 $T1"
  waitdone $1 60; sleep 100
  echo "$T0 $T1" > night-results/$1-t0t1.txt; cp /tmp/db-$1.tsv night-results/$1-db.tsv
  PGPASSWORD=lab psql -h 127.0.0.1 -U sweetgirl soboro -At -F, -c "SELECT cs.course_id,cs.store_id,csm.member_id,csm.seq FROM course_store_memos csm JOIN course_stores cs ON cs.id=csm.course_store_id JOIN courses c ON c.id=cs.course_id WHERE c.title LIKE '[초안]코스%' AND csm.deleted_at IS NULL" > night-results/$1-db-seq.csv
  { echo "커넥션못얻음=$(grep -c 'Connection is not available' app.log) 플러셔실패=$(grep -c 'DB 반영 실패' app.log) 앱=$(pgrep -f api-0.0.1 > /dev/null && echo 살아있음 || echo 죽음)"; curl -s localhost:8091/actuator/prometheus | grep -E '^draft_(backlog|write_total|flush_total)'; grep 'Redis 쓰기 실패\|권한 판정 DB 실패' app.log | grep -o 'cause=[A-Za-z.]*' | sort | uniq -c | sort -rn | head -5; } > night-results/$1-applog.txt
  echo "=== db $1 끝 $(date +%T)"
}
fan n-conflate-t400 api-conflate.jar 400
fan n-conflate-t48 api-conflate.jar 48
db  n-db-relay api-relay.jar 400 1280 60
fan n-1rt api-1rt.jar 400
fan n-base api-v2head.jar 400
db  n-db-relay-t48 api-relay.jar 48 1280 60
job stop; touch NIGHT_DONE; echo "=== 전부 끝 $(date +%T)"
