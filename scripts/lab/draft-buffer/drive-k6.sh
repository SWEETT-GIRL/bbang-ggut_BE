#!/usr/bin/env bash
# 발생기에서 혼자 도는 쓰기 부하(shed.js)만 — 받는 쪽은 다른 서버에서 돈다. 결과 results/<라벨>/out.txt, 끝나면 DONE.
#   drive-k6.sh <앱 host:port> <라벨> <STAGES>
cd ~/k6; APP=$1; L=$2; STAGES=$3
mkdir -p results/$L; [ -f results/$L/DONE ] && mv results/$L/DONE results/$L/DONE.old; exec > results/$L/out.txt 2>&1; ulimit -n 200000
date +'k6 start %H:%M:%S'
(for i in $(seq 1 60); do top -b -n 1 | awk -v t=$(date +%T) '/ k6$/{c+=$9} END{print "#gen-cpu", t, "k6=" c "%"}'; sleep 10; done > results/$L/gen-cpu.txt 2>&1 &)
STAGES="$STAGES" DATA_DIR=. BASE_URL=http://$APP k6 run --quiet --compatibility-mode=base --summary-export=results/$L/summary.json shed.js > results/$L/k6.txt 2>&1
date +'k6 end %H:%M:%S'; sed 's/^/gen load: /' /proc/loadavg
L=$L STAGES=$STAGES python3 - <<'PY'
import json,os
L=os.environ['L']; ST=os.environ['STAGES']
m=json.load(open('results/%s/summary.json'%L))['metrics']
stages=[s.split(':') for s in ST.split(',')]
print('쓰기  단계(편집자)  성공/s  그외/s  PUT p50  p95   max')
for i,(d,t) in enumerate(stages):
    tag='{stage:%d:%s}'%(i+1,t); sec=int(d.rstrip('s'))
    c=lambda n: m.get(n+tag,{}).get('count',0); o=m.get('memo_ok_duration'+tag,{})
    print('      %d:%-6s %s %7.0f %7.0f  %7.0f %5.0f %5.0f'%(i+1,t,'램프' if i%2==0 else '유지',c('memo_ok')/sec,(c('memo_shed')+c('memo_other'))/sec,o.get('med',0),o.get('p(95)',0),o.get('max',0)))
PY
touch results/$L/DONE
