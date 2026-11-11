#!/usr/bin/env bash
# 20초마다: 실행기별 대기 작업 수 · 신호 칸 수 · 덮어쓴 수 · 시작된 스레드 수 · 스레드 그룹별 CPU. 어디에 쌓이는지 본다.
#   watch-queues.sh <라벨> <횟수>
OUT=/tmp/queues-$1.txt; : > $OUT
for i in $(seq 1 ${2:-30}); do
  PID=$(cat ~/lab/app.pid)
  { date +%T | tr '\n' ' '
    curl -s -m 3 localhost:8091/actuator/prometheus | grep -E '^executor_queued_tasks\{|^course_signal_(pending|conflated_total)|^jvm_threads_(live_threads|started_threads_total)|^tomcat_threads_busy' | grep -v -E ' 0\.0$' | sed -E 's/application="Croissant",?//; s/\{\}//' | tr '\n' ' '; echo
    top -b -H -n 2 -d 3 -p $PID -w 200 | awk '/^top -/{it++} it==2 && /^ *[0-9]+ /{g=$12; sub(/[0-9-]+$/,"",g); c[g]+=$9; n[g]++} END{for(k in c) if(c[k]>2) printf "   %s=%.0f%%(%d)", k, c[k], n[k]; print ""}'; } >> $OUT
  sleep 16
done
