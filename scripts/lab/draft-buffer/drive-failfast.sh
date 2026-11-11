#!/usr/bin/env bash
# 발생기에서 혼자 도는 부하. 결과는 results/<라벨>/out.txt, 끝나면 DONE.   drive-failfast.sh <앱 host:port> <라벨> <VUS> <DURATION>
cd ~/k6; APP=$1; L=$2
mkdir -p results/$L; rm -f results/$L/DONE; exec > results/$L/out.txt 2>&1; ulimit -n 200000
VUS=$3 DURATION=$4 DATA_DIR=. BASE_URL=http://$APP k6 run --quiet --compatibility-mode=base --summary-export=results/$L/summary.json failfast.js > results/$L/k6.txt 2>&1
L=$L python3 - <<'PY'
import json,os
m=json.load(open('results/%s/summary.json'%os.environ['L']))['metrics']
for s in ('200','503','other'):
    c=m.get('put_'+s,{}).get('count',0); d=m.get('put_%s_duration'%s,{})
    print('  PUT %-5s %7d건  응답 p50 %5.0f  p95 %5.0f  max %5.0f ms'%(s,c,d.get('med',0),d.get('p(95)',0),d.get('max',0)))
PY
touch results/$L/DONE
