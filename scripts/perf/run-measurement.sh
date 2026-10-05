#!/usr/bin/env bash
# 측정 한 회차를 끝까지 돌린다 — 지표 수집 시작 → k6 → 정합성 검증 → 요약.
#
#   scripts/perf/run-measurement.sh R1 [RATE] [DURATION] [STOCK]
#
# 회차 이름(R1·R2…)이 산출물 파일 이름이 된다. 회차마다 사람이 손으로 DB를 만지면 조건이 흔들리므로
# 쿠폰 초기화는 k6 의 setup() 이 /dev/coupons/reset 으로 한다.
#
# 앱은 반드시 local,lab 프로파일로 떠 있어야 한다:
#   ./gradlew bootRun --args='--spring.profiles.active=local,lab --management.server.port=8091'
# local 단독으로 띄우면 랩이 아닌 DB(15432)를 본다. staging·prod 는 ERROR 로그가 슬랙으로 쏟아지므로 금지.
set -euo pipefail

cd "$(dirname "$0")/../.."

LABEL="${1:?사용: run-measurement.sh <회차이름> [RATE] [DURATION] [STOCK]}"
RATE="${2:-5000}"
DURATION="${3:-10s}"
STOCK="${4:-100}"
MGMT_PORT="${MGMT_PORT:-8091}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

RESULT="scripts/perf/result-${LABEL}.csv"
METRICS="scripts/perf/server-metrics-${LABEL}.log"

if [ ! -f scripts/perf/tokens.csv ]; then
	echo "scripts/perf/tokens.csv 가 없습니다. 먼저 회원 시드와 토큰 생성을 하세요:" >&2
	echo "  docker exec -i postgres-lab psql -U sweetgirl -d soboro -v target=50000 -f - < scripts/perf/seed-coupon-members.sql" >&2
	echo "  docker exec postgres-lab psql -U sweetgirl -d soboro -At -c \"SELECT id FROM member WHERE nickname LIKE '[부하]회원%' ORDER BY id\" > scripts/perf/member-ids.txt" >&2
	echo "  node scripts/perf/generate-tokens.mjs scripts/perf/member-ids.txt scripts/perf/tokens.csv" >&2
	exit 1
fi

if ! curl -sf --max-time 3 "${BASE_URL}/actuator/health" >/dev/null 2>&1 \
	&& ! curl -sf --max-time 3 "http://localhost:${MGMT_PORT}/actuator/health" >/dev/null 2>&1; then
	echo "앱이 떠 있지 않습니다 (${BASE_URL}, 관리포트 ${MGMT_PORT})." >&2
	exit 1
fi

echo "[${LABEL}] 도착률 ${RATE}/s × ${DURATION}, 재고 ${STOCK}장"

scripts/perf/collect-metrics.sh "$METRICS" "$MGMT_PORT" &
COLLECTOR=$!
# 수집기가 죽거나 스크립트가 중간에 끊겨도 남지 않게 한다.
trap 'kill "$COLLECTOR" 2>/dev/null || true' EXIT

BASE_URL="$BASE_URL" RATE="$RATE" DURATION="$DURATION" STOCK="$STOCK" \
	k6 run --out "csv=${RESULT}" scripts/perf/coupon-spike.js || true

kill "$COLLECTOR" 2>/dev/null || true

echo ""
echo "=== 정합성 (전부 통과해야 한다) ==="
docker exec postgres-lab psql -U sweetgirl -d soboro -x -c "
WITH c AS (SELECT id, total_quantity, issued_quantity FROM coupons WHERE name = '[부하측정] 선착순 쿠폰')
SELECT
    c.total_quantity                                                         AS 재고,
    (SELECT count(*) FROM coupon_issues WHERE coupon_id = c.id)              AS 실제_발급_행,
    c.issued_quantity                                                        AS 카운터,
    (SELECT count(*) FROM (SELECT member_id FROM coupon_issues
        WHERE coupon_id = c.id GROUP BY member_id HAVING count(*) > 1) t)    AS 중복_발급,
    ((SELECT count(*) FROM coupon_issues WHERE coupon_id = c.id) <= c.total_quantity
        AND c.issued_quantity = (SELECT count(*) FROM coupon_issues WHERE coupon_id = c.id)) AS 정합성_통과
FROM c;"

echo ""
scripts/perf/summarize.sh "$RESULT" "$METRICS"

echo ""
echo "산출물: $RESULT · $METRICS"
