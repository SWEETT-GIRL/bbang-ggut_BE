#!/usr/bin/env bash
# 앱 서버에서 1초마다: 시각(ms) · Tomcat 바쁜 스레드 · 초안 PUT 상태별 누적 건수와 누적 시간. 나중에 차를 내어 초당 값으로 본다.
#   sample-fo.sh <라벨> <초>
L=$1; N=$2; OUT=/tmp/fo-$L.tsv; : > $OUT
for i in $(seq 1 $N); do
  curl -s -m 2 localhost:8091/actuator/prometheus | awk -v ts=$(date +%s%3N) '
    /^tomcat_threads_busy_threads/ {busy=$2}
    /^http_server_requests_seconds_count/ && /memo-draft/ && /method="PUT"/ { match($0, /status="[0-9]+"/); s=substr($0, RSTART+8, 3); c[s]+=$2 }
    /^http_server_requests_seconds_sum/ && /memo-draft/ && /method="PUT"/ { match($0, /status="[0-9]+"/); s=substr($0, RSTART+8, 3); t[s]+=$2 }
    END { printf "%s\t%d\t%d\t%.3f\t%d\t%.3f\t%d\n", ts, busy, c["200"], t["200"], c["503"], t["503"], c["429"] }' >> $OUT
  sleep 1
done
