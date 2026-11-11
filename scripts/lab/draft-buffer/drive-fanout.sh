#!/usr/bin/env bash
# 발생기에서 혼자 도는 전달 지연 측정. 노트북 연결이 끊겨도 계속 돈다. 결과는 results/<라벨>/out.txt, 끝나면 DONE.
#   drive-fanout.sh <앱 host:port> <라벨> <STAGES> <소켓 수> <프로세스 수>
cd ~/k6; APP=$1; L=$2; STAGES=$3; SOCKETS=$4; PROCS=$5
exec 9> /tmp/drive-fanout.lock; flock -n 9 || exit 0
mkdir -p results/$L; rm -f results/$L/DONE results/$L/out.txt probe-*.log; exec > results/$L/out.txt 2>&1
ulimit -n 200000; sudo sysctl -q -w net.ipv4.ip_local_port_range='1024 65535'
TOTAL=$(echo "$STAGES" | tr ',' '\n' | awk -F'[s:]' '{t+=$1} END{print t}'); PER=$((SOCKETS / PROCS))
for p in $(seq 0 $((PROCS - 1))); do nohup python3 fanout_probe.py $APP $PER $((p * PER)) $((TOTAL + 25)) tokens.csv courses.csv p$p > probe-$p.log 2>&1 < /dev/null & done
for i in $(seq 1 70); do [ "$(cat probe-*.log 2>/dev/null | grep -c READY)" -ge "$PROCS" ] && break; sleep 2; done; grep -h READY probe-*.log
date +'k6 start %H:%M:%S'
STAGES="$STAGES" DATA_DIR=. BASE_URL=http://$APP k6 run --quiet --compatibility-mode=base --summary-export=results/$L/summary.json shed.js > results/$L/k6.txt 2>&1
date +'k6 end %H:%M:%S'; sed 's/^/gen load: /' /proc/loadavg; sleep 12
L=$L STAGES=$STAGES python3 - <<'PY'
import json,glob,re,collections,os
L=os.environ['L']; ST=os.environ['STAGES']
m=json.load(open('results/%s/summary.json'%L))['metrics']
stages=[s.split(':') for s in ST.split(',')]
print('쓰기  단계(편집자)  성공/s  그외/s  PUT p50  p95   max')
for i,(d,t) in enumerate(stages):
    tag='{stage:%d:%s}'%(i+1,t); sec=int(d.rstrip('s'))
    c=lambda n: m.get(n+tag,{}).get('count',0); o=m.get('memo_ok_duration'+tag,{})
    print('      %d:%-6s %s %7.0f %7.0f  %7.0f %5.0f %5.0f'%(i+1,t,'램프' if i%2==0 else '유지',c('memo_ok')/sec,(c('memo_shed')+c('memo_other'))/sec,o.get('med',0),o.get('p(95)',0),o.get('max',0)))
b=collections.defaultdict(lambda: collections.defaultdict(list))
for f in glob.glob('probe-*.log'):
    for line in open(f):
        g=re.match(r'\S+ (\d\d):(\d\d):(\d\d) open=(\d+) recv/s=(\d+) p50=(-?\d+) p95=(-?\d+) p99=(-?\d+) max=(-?\d+) dropped=(\d+)',line)
        if not g: continue
        h,mi,s,op,rc,p50,p95,p99,mx,dr=map(int,g.groups()); x=b['%02d:%02d:%d0'%(h,mi,s//10)]
        for k,v in (('open',op),('recv',rc),('p50',p50),('p95',p95),('p99',p99),('max',mx),('drop',dr)): x[k].append(v)
print('전달  시각(10초)  열린소켓  받은신호/s  지연 p50(최대)  p95(최대)  p99(최대)    max  끊김')
for k in sorted(b):
    x=b[k]; print('      %s %8d %10d %12d %10d %10d %7d %5d'%(k,sum(x['open']),sum(x['recv']),max(x['p50']),max(x['p95']),max(x['p99']),max(x['max']),sum(x['drop'])))
PY
pkill -f fanout_probe.py; touch results/$L/DONE
