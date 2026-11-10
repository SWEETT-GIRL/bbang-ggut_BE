#!/usr/bin/env bash
# R3(2) — Lab 로컬 Redis(maxmemory 4mb, noeviction) 에 플러셔 끄고 초안을 쌓아 가득 찼을 때 503 → 폴백 경로 확인.
set -uo pipefail
S=${DRAFT_LAB_WORK:?}
K=${DRAFT_LAB_KEY:?}; O="-i $K -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR"; J="-o ProxyCommand=ssh $O -W %h:%p ubuntu@${DRAFT_LAB_BASTION:?}"
LAB=${DRAFT_LAB_APP:?}; GEN=${DRAFT_REDIS_3:?}
lab(){ ssh $O "$J" ubuntu@$LAB "$@"; }; gen(){ ssh $O "$J" ubuntu@$GEN "$@"; }
lab 'cat > ~/lab/run-app-tiny.sh <<"EOF2"
#!/usr/bin/env bash
pkill -f api-0.0.1-SNAPSHOT.jar 2>/dev/null; sleep 2; ulimit -n 65535
docker rm -f redis-tiny >/dev/null 2>&1; docker run -d --name redis-tiny -p 127.0.0.1:6390:6379 redis:7.4.11-bookworm redis-server --maxmemory 4mb --maxmemory-policy noeviction --save "" --appendonly no >/dev/null; sleep 2
nohup java -XX:MaxRAMPercentage=70.0 -jar ~/lab/api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --spring.config.additional-location=file:/home/ubuntu/lab/lab.properties --spring.jpa.hibernate.ddl-auto=none --course.draft.redis.mode=standalone --course.draft.redis.host=127.0.0.1 --course.draft.redis.port=6390 --course.draft.redis.password= --course.draft.redis.database=0 --course.draft.flusher-enabled=false > ~/lab/app-tiny.log 2>&1 &
echo $! > ~/lab/app.pid
for i in $(seq 1 150); do curl -sf localhost:8091/actuator/health 2>/dev/null | grep -q UP && { echo "app up tiny after ${i}x2s"; exit 0; }; kill -0 $(cat ~/lab/app.pid) 2>/dev/null || { echo app died; tail -n 15 ~/lab/app-tiny.log | cut -c1-200; exit 1; }; sleep 2; done; echo app not up; exit 1
EOF2
chmod +x ~/lab/run-app-tiny.sh; ~/lab/run-app-tiny.sh'
echo "=== 플러셔 꺼진 채 80명이 0.1초마다 PUT (최대 90초) — Redis 4MB 가 차면 503"; gen "cd ~/k6 && PROMISE=off VUS=80 DURATION=90s SETTLE_SECONDS=0 WRITE_INTERVAL_MS=100 DATA_DIR=. BASE_URL=http://$LAB:8080 k6 run --quiet --summary-export=results/r3-full.json loss.js 2>&1 | grep -E 'givenUp|unavailable503' | tr -d '\n '"; echo
lab 'docker exec redis-tiny redis-cli info memory | grep -E "^(used_memory|maxmemory):" | tr -d "\r" | tr "\n" " "; echo; echo "keys: $(docker exec redis-tiny redis-cli dbsize)"; echo "앱 로그 OOM 503 건수: $(grep -c "OOM command not allowed" ~/lab/app-tiny.log)"; grep -m1 -o "root=.*OOM[^ ]*" ~/lab/app-tiny.log | cut -c1-120; echo "draft_backlog 지표: $(curl -s localhost:8091/actuator/prometheus | grep "^draft_backlog" | cut -d" " -f2)"'
lab 'docker rm -f redis-tiny >/dev/null; cd ~/lab && ./run-app.sh standalone none | tail -1'
echo R3BDONE
