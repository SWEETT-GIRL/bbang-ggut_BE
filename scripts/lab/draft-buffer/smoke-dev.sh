#!/usr/bin/env bash
# Docker 없이 종단 스모크 — dev 서버의 Postgres 를 ssh 터널로 빌리고(bastion → dev WAS → 그 안의 5432),
# Redis 는 로컬 brew Redis(6379, 1번 DB)를 쓴다. 로컬 앱을 띄워 PUT 초안 → 플러셔 → DB → 상세 → STOMP 까지 돈다.
#
#   DEV_DB_PASSWORD=<dev DB 비밀번호> scripts/lab/draft-buffer/smoke-dev.sh
#
# 대가(알고 돌린다):
#   - Flyway 가 dev DB 에 course_store_memos.seq 를 먼저 올린다(ADD COLUMN IF NOT EXISTS, DEFAULT 0 — 지금 dev 앱은 안 깨진다).
#     이 뒤로 V20261103170000 파일은 고치지 않는다(체크섬 불일치 → dev 배포 실패).
#   - 로컬 앱의 스케줄러가 dev DB 를 보고 돈다 — 19:00 cron 같은 시간대를 피하고 끝나면 바로 끈다.
#   - dev DB 에 '[초안]회원'·'[초안]코스' 가 생긴다 — 정리 SQL 은 seed-draft-courses.sql 끝 주석.
#   - 수치는 "원격 dev DB + 로컬 앱" 조합이라 동작 확인용이다. 포폴 숫자는 랩(Docker)에서 다시 잰다.
set -euo pipefail
cd "$(dirname "$0")/../../.."

PEM="${PEM:-$HOME/Desktop/sweetgirl-new.pem}"
DB_PORT="${DB_PORT:-15433}"
# dev DB 비밀번호는 환경변수로만 받는다(application-local.properties 의 datasource 비밀번호와 같다)
: "${DEV_DB_PASSWORD:?DEV_DB_PASSWORD 를 환경변수로 넘기세요}"
export PGPASSWORD="${DEV_DB_PASSWORD}"
DB="host=127.0.0.1 port=${DB_PORT} dbname=soboro user=sweetgirl connect_timeout=5"
BASE="http://localhost:8080"
MGMT="http://localhost:8091"
DIR="scripts/lab/draft-buffer"
LOG="${LOG:-/tmp/bbang-draft-smoke.log}"
PID_FILE=/tmp/bbang-draft-smoke.pid

step() { echo; echo "▶ $*"; }
auth() { echo "Authorization: Bearer $1"; }

step "1. 터널 — bastion 은 WAS:5432 에 못 붙어서(보안그룹) WAS 에 ssh 로 들어가 localhost:5432 를 끌어온다"
if ! lsof -iTCP:"${DB_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
	ssh -i "${PEM}" -o ExitOnForwardFailure=yes -o ConnectTimeout=10 \
		-o ProxyCommand="ssh -i ${PEM} -W %h:%p ubuntu@54.116.12.200" \
		-f -N -L "${DB_PORT}:localhost:5432" ubuntu@10.3.31.174
	sleep 1
fi
pg_isready -h 127.0.0.1 -p "${DB_PORT}" -t 5

step "2. 시드 — 코스 2개 × 멤버 8 × 정류장 5, 매핑·토큰 파일"
psql "${DB}" -v courses=2 -f "${DIR}/seed-draft-courses.sql" | tail -3
psql "${DB}" -At -F, -c "SELECT c.id,
  (SELECT string_agg(store_id::text, '|' ORDER BY visit_order) FROM course_stores WHERE course_id = c.id AND deleted_at IS NULL),
  (SELECT string_agg(member_id::text, '|' ORDER BY joined_at) FROM course_members WHERE course_id = c.id AND deleted_at IS NULL)
  FROM courses c WHERE c.title LIKE '[초안]코스%' AND c.deleted_at IS NULL ORDER BY c.id" > "${DIR}/courses.csv"
psql "${DB}" -At -c "SELECT id FROM member WHERE nickname LIKE '[초안]회원%' ORDER BY id" > "${DIR}/member-ids.txt"
node scripts/perf/generate-tokens.mjs "${DIR}/member-ids.txt" "${DIR}/tokens.csv" | tail -1
echo "courses: $(wc -l < "${DIR}/courses.csv"), tokens: $(wc -l < "${DIR}/tokens.csv")"

step "3. 앱 — dev DB + 로컬 Redis(1번 DB) + write-enabled"
if ! curl -sf "${MGMT}/actuator/health" 2>/dev/null | grep -q UP; then
	[ -f build/libs/Croissant-0.0.1-SNAPSHOT.jar ] || ./gradlew -q bootJar
	nohup java -jar build/libs/Croissant-0.0.1-SNAPSHOT.jar \
		--spring.profiles.active=local \
		--spring.datasource.url="jdbc:postgresql://127.0.0.1:${DB_PORT}/soboro" \
		--course.draft.redis.mode=standalone --course.draft.redis.database=1 \
		--course.draft.write-enabled=true \
		--management.server.port=8091 > "${LOG}" 2>&1 &
	echo $! > "${PID_FILE}"
	for _ in $(seq 1 90); do
		curl -sf "${MGMT}/actuator/health" 2>/dev/null | grep -q UP && break
		sleep 2
	done
	curl -sf "${MGMT}/actuator/health" | grep -q UP || { echo "앱이 안 떴다 — ${LOG} 확인"; tail -30 "${LOG}"; exit 1; }
fi
grep -E "플러셔 시작|Flyway|Successfully applied|seq" "${LOG}" | tail -3 || true

step "4. 종단 — PUT 초안 → 일행 GET drafts → 플러시 → DB·상세 seq → 옛 seq PATCH 는 무시"
IFS=, read -r COURSE STORES MEMBERS < "${DIR}/courses.csv"
STORE="${STORES%%|*}"
ME="${MEMBERS%%|*}"
FRIEND="$(echo "${MEMBERS}" | cut -d'|' -f2)"
TOKEN="$(grep "^${ME}," "${DIR}/tokens.csv" | cut -d, -f2)"
FRIEND_TOKEN="$(grep "^${FRIEND}," "${DIR}/tokens.csv" | cut -d, -f2)"
SEQ="$(date +%s)000"
echo "course=${COURSE} store=${STORE} me=${ME} friend=${FRIEND} seq=${SEQ}"

echo "-- PUT (나):"; curl -s -X PUT "${BASE}/courses/${COURSE}/stores/${STORE}/memo-draft" -H "$(auth "${TOKEN}")" \
	-H 'Content-Type: application/json' -d "{\"content\":\"스모크 ${SEQ}\",\"seq\":${SEQ}}"; echo
echo "-- 옛 seq PUT (무시돼야 함, acceptedSeq=${SEQ}):"; curl -s -X PUT "${BASE}/courses/${COURSE}/stores/${STORE}/memo-draft" -H "$(auth "${TOKEN}")" \
	-H 'Content-Type: application/json' -d "{\"content\":\"옛 글\",\"seq\":$((SEQ - 5))}"; echo
echo "-- GET drafts (일행):"; curl -s "${BASE}/courses/${COURSE}/drafts" -H "$(auth "${FRIEND_TOKEN}")"; echo
echo "-- 4초 뒤 DB (플러시 2초 조용 규칙):"; sleep 4
psql "${DB}" -At -c "SELECT content || ' / seq=' || seq FROM course_store_memos csm JOIN course_stores cs ON cs.id = csm.course_store_id
  WHERE cs.course_id = ${COURSE} AND cs.store_id = ${STORE} AND csm.member_id = ${ME}"
echo "-- 상세 (일행): draftEnabled 와 memos[].seq"
curl -s "${BASE}/courses/${COURSE}" -H "$(auth "${FRIEND_TOKEN}")" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print('draftEnabled =', d['draftEnabled'], '| version =', d['version'])
for s in d['courseStores']:
    for m in s['memos']:
        print('  store', s['storeId'], 'member', m['memberId'], repr(m['content']), 'seq', m['seq'])"
echo "-- 옛 seq 로 PATCH (DB 글이 바뀌면 안 됨):"
curl -s -X PATCH "${BASE}/courses/${COURSE}/stores/${STORE}" -H "$(auth "${TOKEN}")" -H 'Content-Type: application/json' \
	-d "{\"memo\":\"늦게 도착한 옛 글\",\"memoSeq\":$((SEQ - 1000))}"; echo
psql "${DB}" -At -c "SELECT content || ' / seq=' || seq FROM course_store_memos csm JOIN course_stores cs ON cs.id = csm.course_store_id
  WHERE cs.course_id = ${COURSE} AND cs.store_id = ${STORE} AND csm.member_id = ${ME}"
echo "-- Redis 잔량 (비어 있어야 함):"; redis-cli -n 1 keys 'draft:*'
echo "-- 지표:"; curl -s "${MGMT}/actuator/prometheus" | grep -E '^draft_(write_total|flush_total|backlog|socket_sessions)' || true

step "5. STOMP 실연결 — k6 push 모드 20초: 쓰는 쪽 PUT → 받는 쪽 /ws DRAFT 도착 지연"
MODE=push DURATION=20s k6 run "${DIR}/latency-probe.js" 2>&1 | grep -E "companion_visible_latency|ERROR|checks|iterations" | head -8

echo
echo "끝. 앱 종료:  kill \$(cat ${PID_FILE})"
echo "    터널 종료: pkill -f '${DB_PORT}:localhost:5432'"
echo "    dev DB 정리: seed-draft-courses.sql 끝의 DELETE 다섯 줄 (seq 컬럼은 둔다 — 머지되면 Flyway 가 같은 파일을 본다)"
