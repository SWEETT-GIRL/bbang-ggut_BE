#!/usr/bin/env bash
# 인기 코스 쏠림 — 코스 하나(0번)에 구독 소켓 HOT 개 + 편집자 8명, 나머지 코스 625개에는 평소대로(코스당 8명 구독 · 편집자 BG 명).
# 볼 것: 인기 코스의 전달 지연, 그리고 그 때문에 다른 코스가 느려지는가.
# 실험 환경 B(다른 실험과 동시에 돌 수 있게 lab-app 서버만 쓴다): 앱=APP, 받는 쪽 인기=P_HOT · 배경=P_BG, 쓰는 쪽=G2. DB 는 Lab.
#   HOT=2000 [BG=632] [LABEL=hot-2000] bash hot-course.sh
set -uo pipefail
S=${DRAFT_LAB_WORK:?}; source "$(dirname "$0")/net.sh"
APP=${DRAFT_LAB_APP_1:?}; P_HOT=${DRAFT_LAB_APP_2:?}; P_BG=${DRAFT_LAB_APP_3:?}; G2=${DRAFT_LAB_APP_4:?}; HOT=${HOT:-2000}; BG=${BG:-632}; L=${LABEL:-hot-$HOT}; DUR=${DUR:-90}
OUT=$S/hot-results/$L; mkdir -p $OUT
(cd ${DRAFT_LAB_REPO:?} && node scripts/perf/generate-tokens.mjs $S/lab-k6/member-ids.txt $S/lab-k6/tokens.csv >/dev/null)
# 파일은 노트북에서 각 서버로 직접 올린다 — 발생기 경유 내려받기가 조용히 실패해 만료된 토큰으로 돈 적이 있다(2026-11-11)
for h in $P_HOT $P_BG $G2; do
  rsh $h "pkill -f 'api-0.0.1-SNAPSHO[T].jar'; pkill -f 'fanout_probe.p[y]'; mkdir -p ~/probe ~/k6; sudo sysctl -q -w net.ipv4.ip_local_port_range='1024 65535'; true"
  up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/fanout_probe.py ubuntu@$h:probe/ || exit 1
  up $S/lab-k6/tokens.csv $S/lab-k6/courses.csv $S/lab-k6/lib.js $S/lab-k6/scale.js ubuntu@$h:k6/ || exit 1
done
T=$(head -1 $S/lab-k6/tokens.csv | cut -d, -f2)
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$APP "pkill -f 'api-0.0.1-SNAPSHO[T].jar'; cd ~/lab && HEAP_PCT=50 APP_EXTRA='--server.tomcat.max-connections=100000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=400 --logging.level.org.springframework.web=INFO --logging.level.org.springframework.messaging=INFO --logging.level.org.hibernate.type.descriptor.sql=INFO --logging.level.org.hibernate.SQL=INFO --spring.jpa.show-sql=false' ./run-app.sh sentinel none | tail -1; pkill vmstat; nohup vmstat 10 30 > /tmp/vm-$L.txt 2>&1 < /dev/null & true" || exit 1
CODE=$(rsh $P_HOT "curl -s -m 5 -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer '\$(head -1 ~/probe/tokens.csv | cut -d, -f2) http://$APP:8080/courses/1/drafts"); echo "토큰 확인: 앱 응답 $CODE"; [ "$CODE" = 200 ] || { echo "!! 토큰이 안 먹는다 — 중단"; exit 1; }
echo "===== $L 인기 코스 구독 $HOT · 편집자 8 / 배경 코스 625개 구독 5,000 · 편집자 $BG  $(date +%T)"
HP=$(( (HOT + 999) / 1000 ))
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$P_HOT "cd ~/probe; ulimit -n 100000; for p in \$(seq 0 $((HP - 1))); do HOT_COURSE=0 setsid nohup python3 fanout_probe.py $APP:8080 $((HOT / HP)) \$((p * 1000)) $((DUR + 35)) tokens.csv courses.csv hot\$p > hot-\$p.log 2>&1 < /dev/null & done; sleep 1; echo 인기구독시작"
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$P_BG "cd ~/probe; ulimit -n 100000; for p in 0 1 2 3 4; do setsid nohup python3 fanout_probe.py $APP:8080 1000 \$((8 + p * 1000)) $((DUR + 35)) tokens.csv courses.csv bg\$p > bg-\$p.log 2>&1 < /dev/null & done; sleep 1; echo 배경구독시작"
sleep 25
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$G2 "cd ~/k6; ulimit -n 100000; STAGES='10s:$((BG + 8)),${DUR}s:$((BG + 8))' DATA_DIR=. BASE_URL=http://$APP:8080 k6 run --quiet --compatibility-mode=base --summary-export=sum-$L.json scale.js > k6-$L.txt 2>&1; python3 -c \"
import json; m=json.load(open('sum-$L.json'))['metrics']
ok=sum(v['count'] for k,v in m.items() if k.startswith('memo_ok{')); d=[v for k,v in m.items() if k.startswith('memo_ok_duration{')][-1]
print('쓰기: PUT 200', ok, '건 · 유지 구간 p50', round(d['med']), 'p95', round(d['p(95)']), 'ms')\"" | tee $OUT/write.txt
sleep 12
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$P_HOT "cat ~/probe/hot-*.log" > $OUT/hot.txt; ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$P_BG "cat ~/probe/bg-*.log" > $OUT/bg.txt
python3 - $OUT <<'PY'
import sys,re,collections
for name,title in (('hot','인기 코스 구독자'),('bg','다른 코스 구독자')):
    b=collections.defaultdict(lambda: collections.defaultdict(list))
    for line in open(sys.argv[1]+'/%s.txt'%name):
        g=re.match(r'\S+ (\d\d):(\d\d):(\d\d) open=(\d+) recv/s=(\d+) p50=(-?\d+) p95=(-?\d+) p99=(-?\d+) max=(-?\d+) dropped=(\d+)',line)
        if not g: continue
        h,mi,s,op,rc,p50,p95,p99,mx,dr=map(int,g.groups()); x=b['%02d:%02d:%d0'%(h,mi,s//10)]
        for k,v in (('open',op),('recv',rc),('p50',p50),('p95',p95),('max',mx),('drop',dr)): x[k].append(v)
    print('--- %s   시각  소켓  받은신호/s  지연 p50(최대)  p95(최대)  max  끊김'%title)
    for k in sorted(b):
        x=b[k]
        if sum(x['recv'])>0: print('      %s %6d %9d %10d %10d %6d %4d'%(k,sum(x['open']),sum(x['recv']),max(x['p50']),max(x['p95']),max(x['max']),sum(x['drop'])))
PY
ssh $SSHO -o ProxyCommand="$PJ" ubuntu@$APP "pkill vmstat; awk 'NR>3{printf \"%s%s\", (NR>4?\" \":\"\"), 100-\$15}' /tmp/vm-$L.txt | sed 's/^/앱 cpu%: /'; echo; curl -s localhost:8091/actuator/prometheus | grep -E '^course_signal_(conflated_total|pending)' | sed -E 's/\\{[^}]*\\}//' | tr '\n' ' '; echo"
echo HOTDONE
