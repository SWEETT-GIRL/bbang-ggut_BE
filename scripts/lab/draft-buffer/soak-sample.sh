#!/usr/bin/env bash
# 장기 부하 중 1분마다: 새는 것이 있는지 볼 값들. 앱 서버에서 혼자 돈다.   soak-sample.sh <분>
# 열: 시각 · GC 뒤 살아남은 힙(MB) · 쓰는 힙(MB) · 프로세스 RSS(MB) · 스레드 · 열린 FD · 소켓 세션 · draft_backlog · 신호 대기 칸 · 요청 로그 대기열 · PUT 200 누적 · PUT 503 누적 · cpu%
OUT=~/lab/soak.tsv; echo -e "time\tlive_heap_mb\tused_heap_mb\trss_mb\tthreads\tfds\tsessions\tbacklog\tsignal_pending\treqlog_queue\tput200\tput503\tcpu" > $OUT
snap(){ PID=$(cat ~/lab/app.pid); jcmd $PID GC.run > /dev/null 2>&1; sleep 4; { date +%T; curl -s localhost:8091/actuator/prometheus | grep -E '^jvm_memory_used_bytes.*(Old Gen|Metaspace)|^jvm_buffer_memory_used_bytes' | sed -E 's/application="Croissant",?//'; jcmd $PID GC.class_histogram 2>/dev/null | head -34; } > ~/lab/soak-histo-$1.txt; }
for i in $(seq 1 $1); do
  [ $i = 6 ] && snap start
  [ $i = $(($1 - 1)) ] && snap end
  PID=$(cat ~/lab/app.pid); CPU=$(vmstat 1 2 | tail -1 | awk '{print 100-$15}')
  curl -s -m 5 localhost:8091/actuator/prometheus | awk -v t=$(date +%H:%M) -v rss=$(ps -o rss= -p $PID | awk '{print int($1/1024)}') -v fds=$(ls /proc/$PID/fd 2>/dev/null | wc -l) -v cpu=$CPU '
    /^jvm_gc_live_data_size_bytes/ {live=$NF}
    /^jvm_memory_used_bytes/ && /area="heap"/ {used+=$NF}
    /^jvm_memory_used_bytes/ && /Old Gen/ {old=$NF}
    /^jvm_memory_used_bytes/ && /area="nonheap"/ {nonheap+=$NF}
    /^jvm_buffer_memory_used_bytes/ && /direct/ {direct=$NF}
    /^jvm_threads_live_threads/ {th=$NF}
    /^draft_socket_sessions/ {ses=$NF}
    /^draft_backlog/ {bl=$NF}
    /^course_signal_pending/ {sp=$NF}
    /^request_log_queue_size/ {rq=$NF}
    /^http_server_requests_seconds_count/ && /memo-draft/ && /method="PUT"/ { match($0, /status="[0-9]+"/); s=substr($0, RSTART+8, 3); c[s]+=$NF }
    END { printf "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\n", t, live/1048576, used/1048576, rss, th, fds, ses, bl, sp, rq, c["200"], c["503"], cpu }' >> $OUT
  sleep 58
done
