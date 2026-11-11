#!/usr/bin/env bash
# 발생기에서 혼자 도는 쓰기 부하 — 앱 서버 여러 대에 나눠 건다. 결과 results/<라벨>/out.txt, 끝나면 DONE.
#   drive-scale.sh <URLS 쉼표> <라벨> <STAGES>
cd ~/k6; URLS=$1; L=$2; STAGES=$3
mkdir -p results/$L; [ -f results/$L/DONE ] && mv results/$L/DONE results/$L/DONE.old; exec > results/$L/out.txt 2>&1; ulimit -n 200000
date +'k6 start %H:%M:%S'
(vmstat 10 80 > results/$L/gen-vmstat.txt 2>&1 &)
URLS="$URLS" STAGES="$STAGES" DATA_DIR=. BASE_URL=${URLS%%,*} k6 run --quiet --compatibility-mode=base --summary-export=results/$L/summary.json scale.js > results/$L/k6.txt 2>&1
date +'k6 end %H:%M:%S'; pkill vmstat
awk 'NR>3{printf "%s%s", (NR>4?" ":""), 100-$15}' results/$L/gen-vmstat.txt | sed 's/^/발생기 cpu사용%(10초마다): /'; echo
L=$L STAGES=$STAGES python3 - <<'PY'
import json,os
L=os.environ['L']; ST=os.environ['STAGES']
m=json.load(open('results/%s/summary.json'%L))['metrics']
stages=[s.split(':') for s in ST.split(',')]
print('쓰기  단계(편집자)  성공/s  거절/s  그외/s  PUT p50  p95   max')
for i,(d,t) in enumerate(stages):
    tag='{stage:%d:%s}'%(i+1,t); sec=int(d.rstrip('s'))
    c=lambda n: m.get(n+tag,{}).get('count',0); o=m.get('memo_ok_duration'+tag,{})
    print('      %d:%-6s %s %7.0f %7.0f %7.0f  %7.0f %5.0f %5.0f'%(i+1,t,'램프' if i%2==0 else '유지',c('memo_ok')/sec,c('memo_shed')/sec,c('memo_other')/sec,o.get('med',0),o.get('p(95)',0),o.get('max',0)))
PY
touch results/$L/DONE
