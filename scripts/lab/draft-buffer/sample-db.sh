#!/usr/bin/env bash
# 앱 서버에서 1초마다: 시각(ms) · 초안 PUT 200/503 누적 · 503 누적 시간 · draft_backlog · Hikari active/pending · 플러시 누적 · Tomcat 바쁜 스레드 · DB 살아 있나
#   sample-db.sh <라벨> <초>
L=$1; N=$2; OUT=/tmp/db-$L.tsv; : > $OUT
for i in $(seq 1 $N); do
  UP=$(pg_isready -q -h 127.0.0.1 -p 5432 && echo 1 || echo 0)
  curl -s -m 2 localhost:8091/actuator/prometheus | awk -v ts=$(date +%s%3N) -v up=$UP '
    /^tomcat_threads_busy_threads/ {busy=$2}
    /^draft_backlog/ {backlog=$2}
    /^draft_flush_total/ {flush=$2}
    /^hikaricp_connections_active/ {act=$2}
    /^hikaricp_connections_pending/ {pend=$2}
    /^http_server_requests_seconds_count/ && /memo-draft/ && /method="PUT"/ { match($0, /status="[0-9]+"/); s=substr($0, RSTART+8, 3); c[s]+=$2 }
    /^http_server_requests_seconds_sum/ && /memo-draft/ && /method="PUT"/ { match($0, /status="[0-9]+"/); s=substr($0, RSTART+8, 3); t[s]+=$2 }
    END { printf "%s\t%d\t%.3f\t%d\t%.3f\t%d\t%d\t%d\t%d\t%d\t%d\n", ts, c["200"], t["200"], c["503"], t["503"], backlog, act, pend, flush, busy, up }' >> $OUT
  sleep 1
done
