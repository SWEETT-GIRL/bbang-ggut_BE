#!/usr/bin/env bash
# N1 — 네트워크 분단(split-brain). 사람이 `!` 로 실행(iptables·CONFIG SET 은 Claude 권한에서 막힘).
# 마스터(redis-1)를 복제본·감시자 2·3 에서 끊는다(iptables DROP). 감시자 둘이 redis-2 를 승격시키는 동안 standalone 앱(Lab)은 옛 마스터에 계속 쓴다.
# 60초 뒤 분단을 풀면 옛 마스터는 복제본으로 강등 → 그동안 받은 글은 어떻게 되나. min-replicas-to-write 0(지금) vs 1 두 번.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; M1=${DRAFT_REDIS_1:?}; M2=${DRAFT_REDIS_2:?}; M3=${DRAFT_REDIS_3:?}; PW=$(cat $S/redis_pw.txt); C=sweetgirl-prod-redis-redis-draft-1
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }; rssh(){ ssh $O "$J" ubuntu@$1 "${@:2}"; }
m1(){ printf '%s\n' "$PW" | ssh $O "$J" ubuntu@$M1 "read -r PW; D=\"docker exec -e REDISCLI_AUTH=\$PW $C redis-cli -p 6380 --no-auth-warning\"; $1"; }
sent(){ rssh $1 "docker exec sweetgirl-prod-redis-redis-draft-sentinel-1 redis-cli -p 26380 ${*:2}"; }
ms(){ python3 -c 'import time;print(int(time.time()*1000))'; }
verify(){ scp $O "$J" -q "ubuntu@$GEN:~/k6/results/$1.console.log" /tmp/c.log && scp $O "$J" -q /tmp/c.log ubuntu@$LAB:/tmp/c.log
  lab 'export PGPASSWORD=lab; python3 - <<"PY"
import re,subprocess,json
last={}
for line in open("/tmp/c.log",errors="ignore"):
    m=re.search(r"R,(\d+):(\d+):(\d+),(\d+),(\d+)",line)
    if not m: continue
    k=(m[1],m[2],m[3]); seq,acc=int(m[4]),int(m[5]); r=last.setdefault(k,[0,0]); r[0]=max(r[0],seq); r[1]=max(r[1],acc)
q="SELECT cs.course_id,cs.store_id,csm.member_id,csm.seq FROM course_store_memos csm JOIN course_stores cs ON cs.id=csm.course_store_id JOIN courses c ON c.id=cs.course_id WHERE c.title LIKE \x27[초안]코스%\x27 AND csm.deleted_at IS NULL"
rows=subprocess.check_output(["psql","host=127.0.0.1 port=5432 dbname=soboro user=sweetgirl","-At","-F,","-c",q],text=True)
db={tuple(l.split(",")[:3]):int(l.split(",")[3]) for l in rows.splitlines()}
print(json.dumps(dict(cells=len(last), lost_final_text=sum(1 for k,(s,a) in last.items() if db.get(k,0)<s), lost_acked=sum(1 for k,(s,a) in last.items() if a and db.get(k,0)<a))))
PY'; }
failback(){ for i in 1 2 3 4 5; do m=$(sent $M2 sentinel get-master-addr-by-name draft | head -1); [ "$m" = "$M1" ] && break; sent $M2 sentinel failover draft >/dev/null 2>&1; sleep 12; done; echo "  master=$(sent $M2 sentinel get-master-addr-by-name draft | head -1)"; }
heal(){ rssh $M1 "for p in $M2 $M3; do for n in 1 2 3; do sudo iptables -D INPUT -s \$p -j DROP 2>/dev/null; sudo iptables -D OUTPUT -d \$p -j DROP 2>/dev/null; sudo iptables -D DOCKER-USER -s \$p -j DROP 2>/dev/null; sudo iptables -D DOCKER-USER -d \$p -j DROP 2>/dev/null; done; done; echo \"  분단 해제 — 남은 DROP 규칙 \$(sudo iptables -S | grep -c -- '-j DROP')개\""; }
trap 'heal; m1 "\$D config set min-replicas-to-write 0 >/dev/null"; failback' EXIT
for MR in 0; do
  echo "===== N1 min-replicas-to-write=$MR  $(date +%T)"
  failback >/dev/null; lab "cd ~/lab && ./run-app.sh sentinel none" | tail -1
  m1 "\$D config set min-replicas-to-write $MR >/dev/null; \$D config set min-replicas-max-lag 10 >/dev/null; echo \"  설정 min-replicas-to-write=\$(\$D config get min-replicas-to-write | tail -1)\""
  gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=110s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --console-output=results/n1s-$MR.console.log --summary-export=results/n1s-$MR.json loss.js > results/n1s-$MR.txt 2>&1" & KP=$!
  sleep 25; t0=$(ms); rssh $M1 "for p in $M2 $M3; do sudo iptables -I INPUT -s \$p -j DROP; sudo iptables -I OUTPUT -d \$p -j DROP; sudo iptables -I DOCKER-USER -s \$p -j DROP; sudo iptables -I DOCKER-USER -d \$p -j DROP; done; echo \"  분단 시작(redis-1 ↔ redis-2·3 차단, 호스트+컨테이너 경로) DROP 규칙 \$(sudo iptables -S | grep -c -- '-j DROP')개\""
  m=""; for i in $(seq 1 40); do m=$(sent $M2 sentinel get-master-addr-by-name draft 2>/dev/null | head -1); [ -n "$m" ] && [ "$m" != "$M1" ] && break; sleep 0.5; done
  if [ "$m" = "$M1" ] || [ -z "$m" ]; then echo "  !! 승격이 안 일어남(분단 실패) — 이 회차 중단"; heal; wait $KP 2>/dev/null; continue; fi; echo "  승격 → $m, $(( $(ms)-t0 ))ms 뒤 (ssh 왕복 포함)"
  echo "  분단 중 — redis-1 역할: $(m1 '$D role | head -1'), 초당 명령 $(m1 '$D info stats | grep instantaneous_ops_per_sec | cut -d: -f2' | tr -d '\r'), 연결된 복제본 $(m1 '$D info replication | grep connected_slaves | cut -d: -f2' | tr -d '\r') / 감시자가 보는 마스터: $m  ← 마스터가 둘"
  sleep 50; echo "  분단 약 60초 경과 → 해제 $(date +%T)"; heal; sleep 12
  echo "  해제 뒤 redis-1 역할: $(m1 '$D role | head -1')  (감시자가 복제본으로 강등시켰으면 slave)"
  wait $KP 2>/dev/null; sleep 12
  gen "cd ~/k6 && python3 -c \"import json; k=json.loads(open('results/n1s-$MR.txt').read()[open('results/n1s-$MR.txt').read().index('{'):]); print('  k6: 503', k.get('unavailable503'), 'retries', k.get('retries'), 'givenUp', k.get('givenUp'))\""
  lab "grep -c -E 'NOREPLICAS|READONLY' ~/lab/app.log | sed 's/^/  앱 로그 NOREPLICAS\/READONLY: /'; grep -o -E 'root=[a-zA-Z.]+: (NOREPLICAS|READONLY)[^ ]*' ~/lab/app.log | sort | uniq -c | head -3"
  echo -n "  유실(콘솔 vs Lab DB): "; verify n1s-$MR
  failback; m1 '$D -n 3 flushdb >/dev/null'; sleep 5
done
echo N1DONE
