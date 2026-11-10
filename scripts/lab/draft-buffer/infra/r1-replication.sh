#!/usr/bin/env bash
# R1 — 복제 지연과 유실 경계 / min-replicas-to-write. 사람이 `!` 로 실행(복제본 kill·CONFIG SET 은 Claude 권한에서 막힘).
#  (1) Lab 80명 부하 60초 동안 마스터 INFO replication 을 0.5초마다 샘플 → 오프셋 차(bytes)·lag 최대 → "잃을 수 있는 확인된 글" 상한
#  (2) min-replicas-to-write 1 켜고 복제본 컨테이너 kill → 쓰기가 막히는지(503) 60초 → 복제본 start → 설정 원복(0)
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; M1=${DRAFT_REDIS_1:?}; M2=${DRAFT_REDIS_2:?}; PW=$(cat $S/redis_pw.txt); C=sweetgirl-prod-redis-redis-draft-1
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }
m1(){ printf '%s\n' "$PW" | ssh $O "$J" ubuntu@$M1 "read -r PW; D=\"docker exec -e REDISCLI_AUTH=\$PW $C redis-cli -p 6380 --no-auth-warning\"; $1"; }
OUT=$S/r1; mkdir -p $OUT
echo "===== R1 (1) 복제 지연 샘플링 + 80명 부하 60초  $(date +%T)"
lab "cd ~/lab && ./run-app.sh standalone none" | tail -1
m1 'nohup bash -c "for i in \$(seq 1 150); do echo \"\$(date +%T.%N | cut -c1-12) \$(\$D info replication | grep -E \"master_repl_offset|slave0\" | tr -d \"\\r\" | tr \"\\n\" \" \")\"; sleep 0.5; done" > /tmp/repl-samples.txt 2>&1 &'
gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=60s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/r1-load.json loss.js 2>&1 | grep -E 'unavailable503' | tr -d '\n '"; echo
sleep 8; m1 'cat /tmp/repl-samples.txt' > $OUT/repl-samples.txt
python3 - "$OUT/repl-samples.txt" <<'PY'
import re,sys
mx=0; lags=[]; n=0; first=last=None
for line in open(sys.argv[1]):
    m=re.search(r'master_repl_offset:(\d+).*?offset=(\d+),lag=(\d+)',line) or re.search(r'slave0:.*?offset=(\d+),lag=(\d+).*?master_repl_offset:(\d+)',line)
    if not m: continue
    g=m.groups(); 
    if 'master_repl_offset' in line.split('slave0')[0]: mo,so,lag=int(g[0]),int(g[1]),int(g[2])
    else: so,lag,mo=int(g[0]),int(g[1]),int(g[2])
    d=mo-so; mx=max(mx,d); lags.append(lag); n+=1; first=first or mo; last=mo
print(f"samples={n} max_offset_gap_bytes={mx} max_lag_s={max(lags) if lags else None} master_bytes_written={(last or 0)-(first or 0)}")
PY
echo "===== R1 (2) min-replicas-to-write 1 → 복제본 kill → 부하 60초 → 복구  $(date +%T)"
m1 '$D config set min-replicas-to-write 1 && $D config set min-replicas-max-lag 5 && echo "min-replicas-to-write=1 설정"'
ssh $O "$J" ubuntu@$M2 "docker kill --signal=SIGKILL $C >/dev/null && echo '복제본 kill'"
sleep 6; m1 '$D info replication | grep -E "connected_slaves|min_slaves_good" | tr -d "\r" | tr "\n" " "'; echo
gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=40s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/r1-minrep.json loss.js 2>&1 | grep -E 'retries|givenUp|unavailable503' | tr -d '\n '"; echo
lab "grep -c 'NOREPLICAS' ~/lab/app.log | sed 's/^/앱 로그 NOREPLICAS 503 건수: /'"
echo "--- 복구"; ssh $O "$J" ubuntu@$M2 "docker start $C >/dev/null && echo '복제본 start'"; sleep 8
m1 '$D config set min-replicas-to-write 0 && $D info replication | grep -E "connected_slaves|slave0" | tr -d "\r" | tr "\n" " "'; echo
m1 '$D -n 3 flushdb >/dev/null && echo "Lab 초안(DB3) 비움"'
echo R1DONE
