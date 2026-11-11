#!/usr/bin/env bash
# 발생기에서 혼자 도는 장기 부하: 구독 소켓 12,000개(전달 지연 기록) + 편집자 VUS 명. 끝나면 results/soak/DONE.
#   drive-soak.sh <앱 host:port> <VUS> <초>
cd ~/k6; APP=$1; VUS=$2; SEC=$3
mkdir -p results/soak; [ -f results/soak/DONE ] && mv results/soak/DONE results/soak/DONE.old; ulimit -n 200000
for p in 0 1 2; do nohup python3 fanout_probe.py $APP 4000 $((p * 4000)) $((SEC + 40)) tokens.csv courses.csv s$p > results/soak/probe-$p.log 2>&1 < /dev/null & done
sleep 25
VUS=$VUS DURATION=${SEC}s DATA_DIR=. BASE_URL=http://$APP k6 run --quiet --compatibility-mode=base --summary-export=results/soak/summary.json failfast.js > results/soak/k6.txt 2>&1
sleep 20; touch results/soak/DONE
