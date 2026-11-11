#!/usr/bin/env bash
# 발생기에서 혼자 도는 유실 판정용 부하(loss.js, 약속 켬). 결과는 results/<라벨>/, 끝나면 DONE.   drive-loss.sh <앱 host:port> <라벨> <VUS> <DURATION>
cd ~/k6; APP=$1; L=$2
mkdir -p results/$L; rm -f results/$L/DONE; ulimit -n 200000
PROMISE=on VUS=$3 DURATION=$4 SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$APP k6 run --quiet --compatibility-mode=base --console-output=results/$L/console.log --summary-export=results/$L/summary.json loss.js > results/$L/k6.txt 2>&1
touch results/$L/DONE
