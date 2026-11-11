#!/usr/bin/env bash
# 대규모 재접속 폭주 — 소켓 N개가 붙은 앱(Lab)을 죽였다 살린다. 지터 없음(BASE 만) 대 지터 있음.
# 실험 환경 A(Lab 앱 + 발생기). 인기 코스 실험(환경 B: lab-app)과 동시에 돌 수 있다.
#   N=50000 bash storm.sh
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
N=${N:-50000}; OUT=$S/storm-results; mkdir -p $OUT
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/storm_sockets.py ubuntu@$GEN:k6/ || exit 1
rsh $LAB "cp ~/lab/api-inline.jar ~/lab/api-0.0.1-SNAPSHO?.jar" || exit 1
ARGS="--server.tomcat.max-connections=300000 --server.tomcat.accept-count=20000 --server.tomcat.threads.max=400 --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false"
for JIT in 0 5000; do
  L=storm-j$JIT
  rsh $LAB "cd ~/lab && HEAP_PCT=60 APP_EXTRA='$ARGS' ./run-app.sh sentinel none | tail -1" || exit 1
  echo "===== $L 소켓 $N · 끊기면 1초 + 0~$((JIT / 1000))초 뒤 재접속  $(date +%T)"
  ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$GEN "cd ~/k6; ulimit -n 200000; sudo sysctl -q -w net.ipv4.ip_local_port_range='1024 65535'; setsid nohup python3 storm_sockets.py $LAB:8080 $N 230 1000 $JIT tokens.csv courses.csv > $L.log 2>&1 < /dev/null & sleep 1; echo 소켓시작"
  sleep 75
  rsh $GEN "tail -1 ~/k6/$L.log | sed 's/^/  재시작 직전: /'"
  T0=$(rsh $LAB "date +%s%3N | head -1"); echo "  앱 죽임 t0=$T0"
  rsh $LAB "cd ~/lab && HEAP_PCT=60 APP_EXTRA='$ARGS' ./run-app.sh sentinel none | tail -1"
  T1=$(rsh $LAB "date +%s%3N | head -1"); echo "  앱 다시 뜸 t1=$T1 (재기동 $(( (T1 - T0) / 1000 ))초)"
  rsh $LAB "nohup vmstat 2 40 > /tmp/vm-$L.txt 2>&1 < /dev/null & true"
  sleep 95
  ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$GEN "cat ~/k6/$L.log" > $OUT/$L.txt
  T0=$T0 T1=$T1 N=$N python3 - $OUT/$L.txt <<'PY'
import sys,os,re
t0=int(os.environ['T0']); t1=int(os.environ['T1']); N=int(os.environ['N'])
rows=[]
for l in open(sys.argv[1]):
    m=re.match(r'(\d{13}) open=(\d+) try=(\d+) ok=(\d+) fail=(\d+)',l)
    if m: rows.append(tuple(map(int,m.groups())))
    elif l.startswith('RECONNECT'): print('  '+l.strip())
print('  초(앱이 다시 뜬 시점 기준)  붙은 소켓  접속 시도/2초  성공  실패')
t50=t95=t100=None; tries=fails=0; peak=0
for ts,op,tr,ok,fl in rows:
    s=(ts-t1)/1000.0
    if ts>=t0: tries+=tr; fails+=fl
    if ts>=t1:
        peak=max(peak,tr)
        if t50 is None and op>=0.5*N: t50=s
        if t95 is None and op>=0.95*N: t95=s
        if t100 is None and op>=0.999*N: t100=s
    if -6<=s<=40 or (s>40 and int(s)%10<2): print('  %8.0f %10d %10d %7d %6d'%(s,op,tr,ok,fl))
print('  다시 뜬 뒤 50%% 복귀 %s초 · 95%% %s초 · 전부 %s초 · 2초당 최대 시도 %d · 죽은 뒤 총 시도 %d(소켓당 %.1f회) · 실패 %d'%(t50 and round(t50,1),t95 and round(t95,1),t100 and round(t100,1),peak,tries,tries/N,fails))
PY
  rsh $LAB "pkill vmstat; awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/  앱 cpu%(2초마다, 다시 뜬 뒤): /'; echo; grep -c ' ERROR' ~/lab/app.log | sed 's/^/  ERROR 로그: /'; curl -s localhost:8091/actuator/prometheus | grep '^draft_socket_sessions' | sed 's/^/  /'"
  rsh $GEN "pkill -f storm_sockets.p[y]; true"
done
echo STORMDONE
