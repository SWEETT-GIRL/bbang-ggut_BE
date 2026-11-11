#!/usr/bin/env bash
# 발생기 쪽 — Lab 의 job.txt 를 1초마다 보고 새 일이 오면 부하를 건다. 결과 폴더는 8090 으로 내준다(Lab 이 DONE 을 본다).
cd ~/k6; exec >> night-gen.log 2>&1; LAB=${DRAFT_LAB_APP:?}; last=""
STAGES="20s:160,40s:160,20s:320,40s:320,20s:640,40s:640,20s:1280,40s:1280,20s:2500,40s:2500"
pgrep -f 'http.server 809[0]' > /dev/null || (nohup python3 -m http.server 8090 --bind ${DRAFT_LAB_GEN:?} > /dev/null 2>&1 < /dev/null &)
while true; do
  line=$(curl -s -m 3 http://$LAB:8090/job.txt); id=${line%% *}
  if [ -n "$id" ] && [ "$id" != "$last" ] && [[ "$id" =~ ^[0-9]+$ ]]; then
    last=$id; set -- $line; echo "$(date +%T) job: $*"
    case $2 in
      fan) ./drive-fanout.sh $LAB:8080 $3 "$STAGES" 12000 3 ;;
      loss) ./drive-loss.sh $LAB:8080 $3 $4 ${5}s ;;
      stop) echo 끝; exit 0 ;;
    esac
  fi
  sleep 1
done
