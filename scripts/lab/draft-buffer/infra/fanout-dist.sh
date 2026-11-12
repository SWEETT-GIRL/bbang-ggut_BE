#!/usr/bin/env bash
# 전달 지연 — 받는 쪽(fanout_probe.py)을 발생기와 다른 서버들(PROBES)로 흩어 놓고 잰다.
# 받는 프로그램이 발생기와 CPU 를 나눠 쓰면 받는 쪽이 밀려 "서버 지연"처럼 찍힌다(2026-11-11 확인) — 그걸 없앤 측정.
# 시계: 쓰는 쪽(k6, 발생기)과 받는 쪽이 다른 서버라 시계 차가 섞인다. AWS chrony 동기(1ms 안쪽)를 믿고, 시작 때 각 서버의 offset 을 찍어 둔다.
#   JAR=api-relay.jar LABEL=dist-relay [STAGES=..] [TOMCAT_THREADS=400] [PER_PROC=1000] [PROCS_PER_HOST=3] bash fanout-dist.sh
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
PROBES=${PROBES:-"${DRAFT_LAB_APP_1:?} ${DRAFT_LAB_APP_2:?} ${DRAFT_LAB_APP_3:?} ${DRAFT_LAB_APP_4:?}"}
STAGES=${STAGES:-"20s:160,40s:160,20s:320,40s:320,20s:640,40s:640,20s:1280,40s:1280,20s:2500,40s:2500"}; L=${LABEL:-dist}; JAR=${JAR:-api-relay.jar}
PER=${PER_PROC:-1000}; PP=${PROCS_PER_HOST:-3}
TOTAL=$(echo "$STAGES" | tr ',' '\n' | awk -F'[s:]' '{t+=$1} END{print t}')
OUT=$S/dist-results/$L; mkdir -p $OUT
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/shed.js $S/lab-k6/fanout_probe.py $S/lab-k6/drive-k6.sh ubuntu@$GEN:k6/ || exit 1
rsh $GEN "chmod +x ~/k6/drive-k6.sh; pgrep -f 'http.server 809[0]' > /dev/null || (cd ~/k6 && nohup python3 -m http.server 8090 --bind $GEN > /dev/null 2>&1 < /dev/null &); sleep 1; echo 발생기준비" || exit 1
rsh $LAB "cp ~/lab/$JAR ~/lab/api-0.0.1-SNAPSHO?.jar" || exit 1
rsh $LAB "cd ~/lab && ${APP_ENV:-} HEAP_PCT=50 APP_EXTRA='${APP_ARGS:-} --server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=${TOMCAT_THREADS:-400} --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1" || exit 1
echo "===== $L jar=$JAR 받는 서버=$(echo $PROBES | wc -w)대 × ${PP}프로세스 × ${PER}소켓  stages=$STAGES  $(date +%T)"
j=0
for h in $PROBES; do
  rsh $h "mkdir -p ~/probe; pkill -f 'fanout_probe.p[y]'; true" >/dev/null
  # 파일은 노트북에서 직접 올린다 — 발생기 경유 내려받기가 조용히 실패하면 만료된 토큰으로 붙지도 못한다(2026-11-12)
  up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/fanout_probe.py ubuntu@$h:probe/ || exit 1
  rsh $h "cd ~/probe; ulimit -n 100000; sudo sysctl -q -w net.ipv4.ip_local_port_range='1024 65535'; for p in \$(seq 0 $((PP - 1))); do nohup python3 fanout_probe.py $LAB:8080 $PER \$(( ($j * $PP + p) * $PER )) $((TOTAL + 45)) tokens.csv courses.csv h${j}p\$p > probe-\$p.log 2>&1 < /dev/null & done; echo \"받는 서버 $h 시작, 시계차 \$(chronyc tracking 2>/dev/null | awk '/System time/{print \$4*1000\"ms\", \$6}')\"" || exit 1
  j=$((j + 1))
done
rsh $GEN "chronyc tracking 2>/dev/null | awk '/System time/{print \"발생기 시계차\", \$4*1000\"ms\", \$6}'"
sleep 30
OPEN=0; for h in $PROBES; do R=$(rsh $h "grep -h READY ~/probe/probe-*.log | tr '\n' ' '"); echo "$R"; for n in $(echo "$R" | grep -o 'open=[0-9]*' | cut -d= -f2); do OPEN=$((OPEN + n)); done; done
WANT=$(( $(echo $PROBES | wc -w) * PP * PER )); echo "붙은 소켓 $OPEN / $WANT"; [ "$OPEN" -ge "$WANT" ] || { echo "!! 받는 소켓이 다 붙지 않았다 — 중단"; for h in $PROBES; do rsh $h "pkill -f 'fanout_probe.p[y]'; true"; done; exit 1; }
rsh $LAB "pkill vmstat; echo 정리" >/dev/null
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$LAB "cd ~/lab; setsid nohup ./watch-queues.sh $L 40 > /dev/null 2>&1 < /dev/null & nohup vmstat 10 60 > /tmp/vm-$L.txt 2>&1 < /dev/null & sleep 1; echo 감시" >/dev/null
rsh $GEN "nohup ~/k6/drive-k6.sh $LAB:8080 $L '$STAGES' > /dev/null 2>&1 < /dev/null & echo 부하시작"
sleep $((TOTAL - 20))
for i in $(seq 1 40); do rsh $GEN "test -f ~/k6/results/$L/DONE" 2>/dev/null && break; sleep 5; done
sleep 15
rsh $GEN "cat ~/k6/results/$L/out.txt" | tee $OUT/write.txt
j=0; for h in $PROBES; do rsh $h "cat ~/probe/probe-*.log; top -b -n 1 | awk '/python3/{c+=\$9} END{print \"#cpu python합=\" c \"%\"}'; pkill -f fanout_probe.p[y]; true" > $OUT/probe-$j.txt 2>/dev/null; j=$((j + 1)); done
python3 - "$OUT" <<'PY'
import sys,glob,re,collections
b=collections.defaultdict(lambda: collections.defaultdict(list))
for f in glob.glob(sys.argv[1]+'/probe-*.txt'):
    for line in open(f):
        g=re.match(r'\S+ (\d\d):(\d\d):(\d\d) open=(\d+) recv/s=(\d+) p50=(-?\d+) p95=(-?\d+) p99=(-?\d+) max=(-?\d+) dropped=(\d+)',line)
        if not g: continue
        h,mi,s,op,rc,p50,p95,p99,mx,dr=map(int,g.groups()); x=b['%02d:%02d:%d0'%(h,mi,s//10)]
        for k,v in (('open',op),('recv',rc),('p50',p50),('p95',p95),('p99',p99),('max',mx),('drop',dr)): x[k].append(v)
print('전달  시각(10초)  열린소켓  받은신호/s  지연 p50(중앙/최대)  p95(최대)  p99(최대)    max  끊김')
for k in sorted(b):
    x=b[k]; p=sorted(x['p50'])
    print('      %s %8d %10d %9d/%-8d %9d %10d %7d %5d'%(k,sum(x['open']),sum(x['recv']),p[len(p)//2],max(x['p50']),max(x['p95']),max(x['p99']),max(x['max']),sum(x['drop'])))
PY
rsh $LAB "awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/lab cpu사용%(10초마다): /'; echo; echo '--- 20초마다: 실행기 대기 · 신호 칸 · 스레드 그룹 CPU%(스레드 수)'; cat /tmp/queues-$L.txt" | cut -c1-330 | tee $OUT/lab.txt
echo DISTDONE
