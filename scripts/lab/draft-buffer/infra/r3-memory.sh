#!/usr/bin/env bash
# R3 — 메모리 용량. (1) 운영 초안 Redis(DB 3, Lab 앱이 쓰는 칸)에서 코스 Hash·due 키의 MEMORY USAGE 를 재서 코스당 바이트 → 256MB 용량 환산.
#      (2) Lab 서버에 maxmemory 4mb noeviction Redis 를 따로 띄우고 Lab 앱을 거기에 붙여, 가득 찼을 때 503 → 앱 PATCH 폴백 경로가 열리는지 k6 로 확인.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}; PW=$(cat $S/redis_pw.txt)
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }
echo "===== (1) 코스당 메모리 — 부하 10초 걸어 초안을 쌓은 직후 잰다  $(date +%T)"
lab "cd ~/lab && ./run-app.sh standalone none" | tail -1
gen "cd ~/k6 && PROMISE=on VUS=80 DURATION=12s SETTLE_SECONDS=0 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet loss.js >/dev/null 2>&1" & sleep 9
printf '%s\n' "$PW" | ssh $O "$J" ubuntu@${DRAFT_REDIS_1:?} 'read -r PW; D="docker exec -e REDISCLI_AUTH=$PW sweetgirl-prod-redis-redis-draft-1 redis-cli -p 6380 -n 3 --no-auth-warning"
 echo "keys(DB3)=$($D dbsize)"; tot=0; n=0; for k in $($D --scan --pattern "draft:{*" | head -20); do b=$($D memory usage "$k"); f=$($D hlen "$k"); echo "  $k fields=$f bytes=$b"; tot=$((tot+b)); n=$((n+1)); done; [ $n -gt 0 ] && echo "코스 Hash 평균 bytes=$((tot/n)) (n=$n)"; echo "draft:due bytes=$($D memory usage draft:due) members=$($D zcard draft:due)"; echo "used_memory=$($D info memory | grep -E "^used_memory:" | tr -d "\r")"'
sleep 8
echo "===== (2) Lab 로컬 Redis(maxmemory 4mb, noeviction) 로 가득 참 재현  $(date +%T)"
lab 'sg docker -c "docker rm -f redis-tiny >/dev/null 2>&1; docker run -d --name redis-tiny -p 127.0.0.1:6390:6379 redis:7.4.11-bookworm redis-server --maxmemory 4mb --maxmemory-policy noeviction --save \"\" --appendonly no >/dev/null"; sleep 2; docker exec redis-tiny redis-cli ping'
lab "cd ~/lab && pkill -f api-0.0.1-SNAPSHOT.jar; sleep 2; nohup java -XX:MaxRAMPercentage=70.0 -jar ~/lab/api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --spring.config.additional-location=file:/home/ubuntu/lab/lab.properties --spring.jpa.hibernate.ddl-auto=none --course.draft.redis.mode=standalone --course.draft.redis.host=127.0.0.1 --course.draft.redis.port=6390 --course.draft.redis.password= --course.draft.redis.database=0 --course.draft.flusher-enabled=false > ~/lab/app-tiny.log 2>&1 & for i in \$(seq 1 150); do curl -sf localhost:8091/actuator/health | grep -q UP && { echo app up tiny; break; }; sleep 2; done"
# 플러셔를 꺼서 초안이 Redis 에 쌓이기만 하게 → 가득 찰 때까지 PUT
gen "cd ~/k6 && PROMISE=off VUS=160 DURATION=90s SETTLE_SECONDS=0 WRITE_INTERVAL_MS=100 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/r3-full.json loss.js 2>&1 | grep -E 'givenUp|unavailable503' | tr -d '\n '"; echo
lab 'docker exec redis-tiny redis-cli info memory | grep -E "^(used_memory|maxmemory):" | tr -d "\r" | tr "\n" " "; echo; docker exec redis-tiny redis-cli dbsize; grep -c "OOM command not allowed" ~/lab/app-tiny.log | sed "s/^/앱 로그 OOM 503 건수: /"; grep -m1 "root=.*OOM" ~/lab/app-tiny.log | grep -o "root=.*" | cut -c1-120'
lab 'docker rm -f redis-tiny >/dev/null; cd ~/lab && ./run-app.sh standalone none | tail -1'
echo R3DONE
