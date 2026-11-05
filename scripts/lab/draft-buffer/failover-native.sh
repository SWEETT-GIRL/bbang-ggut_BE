#!/usr/bin/env bash
# Docker 없이 페일오버 유실을 잰다. brew Redis 로 master·replica·sentinel 3개를 띄우고, 앱은 로컬, DB 는 dev 터널을 쓴다.
#
#   DEV_DB_PASSWORD=... LOCAL_PROPS=<application-local.properties 경로> JWT_SECRET=... \
#     FIREBASE_FILE=<테스트용 firebase json> scripts/lab/draft-buffer/failover-native.sh <작업 폴더>
#
# 응답 속도 비교에는 쓰지 않는다(원격 DB). 보는 것은 유실 칸 수와 승격 시간이다.
# 앱은 초안을 Redis 1번 DB 에 쓴다. redis-cli 는 -n 1 없이 보면 항상 비어 보인다.
# 플러셔가 꺼내 든 코스는 마스터가 죽으면 임대(30초) 뒤에 돌아온다. 1번 DB 가 빌 때까지 기다린 뒤 비교한다.
set -uo pipefail
S=$1; mkdir -p "$S/k6" "$S/results"; REPO=$(cd "$(dirname "$0")/../../.." && pwd); D0=$REPO/scripts/lab/draft-buffer
: "${DEV_DB_PASSWORD:?}" "${LOCAL_PROPS:?}" "${JWT_SECRET:?}" "${FIREBASE_FILE:?}"
cp "$D0/lib.js" "$S/k6/"; cp "$D0/failover.js" "$S/k6/loss.js"
P=${PEM:-$HOME/Desktop/sweetgirl-new.pem}; ROUNDS="${ROUNDS:-on off on off on off}"; VUS=${VUS:-80}; DUR=${DUR:-60}; KILLAT=$((DUR-3))
export PGPASSWORD="$DEV_DB_PASSWORD"
DB="host=127.0.0.1 port=15433 dbname=soboro user=sweetgirl connect_timeout=5"
APP=""; TUN=""; R=$S/redis; mkdir -p $R
cleanup(){ [ -n "$APP" ] && kill $APP 2>/dev/null; [ -n "$TUN" ] && kill $TUN 2>/dev/null; for p in 6380 6381 26379 26380 26381; do redis-cli -p $p shutdown nosave >/dev/null 2>&1; done; echo "정리 끝"; }
trap cleanup EXIT
ssh -i $P -o BatchMode=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -o ProxyCommand="ssh -i $P -o BatchMode=yes -W %h:%p ubuntu@54.116.12.200" -N -L 15433:localhost:5432 ubuntu@10.3.31.174 & TUN=$!
for i in $(seq 1 60); do pg_isready -q -h 127.0.0.1 -p 15433 && break; sleep 0.5; done
psql "$DB" -At -c "SELECT 'db ok', count(*) FROM courses WHERE title LIKE '[초안]코스%' AND deleted_at IS NULL" || exit 1
psql "$DB" -At -c "SELECT id FROM member WHERE nickname LIKE '[초안]회원%' ORDER BY id" > $S/k6/member-ids.txt
(cd $REPO && JWT_SECRET="$JWT_SECRET" node scripts/perf/generate-tokens.mjs $S/k6/member-ids.txt $S/k6/tokens.csv | tail -1)
psql "$DB" -At -F, -c "SELECT c.id,(SELECT string_agg(store_id::text,'|' ORDER BY visit_order) FROM course_stores WHERE course_id=c.id AND deleted_at IS NULL),(SELECT string_agg(member_id::text,'|' ORDER BY joined_at) FROM course_members WHERE course_id=c.id AND deleted_at IS NULL) FROM courses c WHERE c.title LIKE '[초안]코스%' AND c.deleted_at IS NULL ORDER BY c.id" > $S/k6/courses.csv
for port in 6380 6381; do mkdir -p $R/$port; cat > $R/$port/redis.conf <<EOF
port $port
bind 127.0.0.1
dir $R/$port
appendonly yes
appendfsync everysec
maxmemory 256mb
maxmemory-policy noeviction
save ""
daemonize yes
pidfile $R/$port/redis.pid
logfile $R/$port/redis.log
EOF
done; echo "replicaof 127.0.0.1 6380" >> $R/6381/redis.conf
redis-server $R/6380/redis.conf; redis-server $R/6381/redis.conf
for port in 26379 26380 26381; do mkdir -p $R/$port; cat > $R/$port/s.conf <<EOF
port $port
bind 127.0.0.1
dir $R/$port
daemonize yes
pidfile $R/$port/s.pid
logfile $R/$port/s.log
sentinel monitor draft 127.0.0.1 6380 2
sentinel down-after-milliseconds draft 5000
sentinel failover-timeout draft 10000
sentinel parallel-syncs draft 1
EOF
redis-sentinel $R/$port/s.conf; done; sleep 3
redis-cli -p 26379 sentinel get-master-addr-by-name draft | tr '\n' ' '; redis-cli -p 26379 sentinel ckquorum draft
JAR=$(ls $REPO/api/build/libs/*.jar | grep -v plain | head -1); echo jar=$JAR
nohup java -jar "$JAR" --spring.profiles.active=local --spring.config.additional-location=file:$LOCAL_PROPS --jwt.secret="$JWT_SECRET" \
 --spring.datasource.url=jdbc:postgresql://127.0.0.1:15433/soboro --spring.flyway.enabled=false --spring.jpa.hibernate.ddl-auto=none \
 --app.scheduling.enabled=false --app.startup-data-maintenance.enabled=false --shedlock.enabled=false --monthly-report.auto-preparation.enabled=false --menu.classification.enabled=false --search-miss.triage.enabled=false --slack.notification.enabled=false --ops.alert.server-error.enabled=false --store.search.autocomplete-cache.enabled=false --store.trending.enabled=false --recommendation.reason.llm.enabled=false \
 --course.draft.redis.mode=sentinel --course.draft.redis.sentinel-master=draft --course.draft.redis.sentinel-nodes=127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381 --course.draft.write-enabled=true --management.server.port=8091 --firebase.config.path=file:$FIREBASE_FILE > $S/app.log 2>&1 & APP=$!
for i in $(seq 1 120); do curl -sf localhost:8091/actuator/health 2>/dev/null | grep -q UP && break; kill -0 $APP || { echo 앱죽음; tail -30 $S/app.log | cut -c1-200; exit 1; }; sleep 2; done
curl -sf localhost:8091/actuator/health | grep -q UP || { echo 앱안뜸; tail -30 $S/app.log | cut -c1-200; exit 1; }; echo app up; grep -m2 -E 'jdbc:postgresql|플러셔 시작' $S/app.log | cut -c1-140
n=0; for mode in $ROUNDS; do n=$((n+1)); D=$S/results/$n-$mode; mkdir -p $D; m0=$(redis-cli -p 26379 sentinel get-master-addr-by-name draft | sed -n 2p); echo "=== round $n $mode master=$m0 $(date +%T)"
 ( cd $S/k6 && PROMISE=$mode VUS=$VUS DURATION=${DUR}s SETTLE_SECONDS=1 DATA_DIR=. BASE_URL=http://localhost:8080 k6 run --quiet --console-output=$D/console.log loss.js > $D/k6.txt 2>&1 ) & K=$!
 sleep $KILLAT; p=$(cat $R/$m0/redis.pid); t0=$(python3 -c 'import time;print(int(time.time()*1000))'); kill -9 $p; rm -f $R/$m0/redis.pid
 while :; do m1=$(redis-cli -p 26380 sentinel get-master-addr-by-name draft | sed -n 2p); [ "$m1" != "$m0" ] && break; sleep 0.05; done; t1=$(python3 -c 'import time;print(int(time.time()*1000))'); echo "promote_ms=$((t1-t0)) new=$m1" | tee $D/promote.txt
 wait $K; t2=$(python3 -c 'import time;print(int(time.time()*1000))'); for i in $(seq 1 180); do d=$(redis-cli -p $m1 -n 1 zcard draft:due); f=$(redis-cli -p $m1 -n 1 zcard draft:inflight); h=$(redis-cli -p $m1 -n 1 --scan --pattern 'draft:{*' | wc -l | tr -d ' '); [ "$d$f$h" = "000" ] && break; sleep 0.5; done; t3=$(python3 -c 'import time;print(int(time.time()*1000))'); echo "db1_size_at_end=$(redis-cli -p $m1 -n 1 dbsize) due=$d inflight=$f hashes=$h drain_ms=$((t3-t2))" | tee -a $D/promote.txt
 sleep 2; python3 $D0/compare-loss.py $D/console.log $D/result.json; sed -n '/"retries"/,/}/p' $D/k6.txt | head -12 | tr -d '\n '; echo; grep -c ' ERROR' $S/app.log
 redis-server $R/$m0/redis.conf; for i in $(seq 1 40); do redis-cli -p $m1 info replication | grep -q 'connected_slaves:1' && break; sleep 1; done; redis-cli -p $m1 info replication | grep -E 'role|connected_slaves' | tr -d '\r' | tr '\n' ' '; echo; sleep 12
done; echo ALLDONE
