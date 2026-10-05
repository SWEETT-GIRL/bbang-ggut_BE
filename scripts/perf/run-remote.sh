#!/usr/bin/env bash
# 부하 생성기에서 원격 앱을 때리는 측정 한 회차.
#
#   scripts/perf/run-remote.sh <회차이름> [RATE] [DURATION] [STOCK]
#
# run-measurement.sh 와 나뉘는 이유: 저쪽은 앱·DB·k6 가 한 머신에 있는 로컬 랩 전용이다.
# 여기서는 k6 만 이 머신에 있고 앱·DB·actuator 는 건너편에 있다.
#
# ## 왜 부하 생성기를 분리하나
# 로컬(맥)에서 앱과 k6 를 같이 돌렸더니 시스템 CPU 100% 중 JVM 이 19% 밖에 못 받았다.
# 그 상태의 처리량은 서버 용량이 아니라 "CPU 를 19% 받았을 때의 값"이고, 같은 설정으로
# 두 번 재면 검색 p99 가 1.4배씩 흔들렸다. 그래서 생성기를 따로 뽑았다.
#
# ## 필요한 환경변수
#   APP_HOST    앱의 사설 IP (예: 10.3.31.174)          ※ 필수
#   MGMT_PORT   actuator 포트 (기본 8081)
#   PERF_KEY    /dev/coupons/reset 용 대시보드 키       ※ staging 필수
#   COUPON_ID   리셋을 못 쓸 때 쓸 쿠폰 id (선택)
set -euo pipefail

cd "$(dirname "$0")"

LABEL="${1:?사용: run-remote.sh <회차이름> [RATE] [DURATION] [STOCK]}"
RATE="${2:-5000}"
DURATION="${3:-10s}"
STOCK="${4:-500}"

APP_HOST="${APP_HOST:?APP_HOST 에 앱의 사설 IP 를 주세요 (예: APP_HOST=10.3.31.174)}"
APP_PORT="${APP_PORT:-8080}"
MGMT_PORT="${MGMT_PORT:-8081}"
BASE_URL="http://${APP_HOST}:${APP_PORT}"

RESULT="result-${LABEL}.csv"
METRICS="server-metrics-${LABEL}.log"

if [ ! -f tokens.csv ]; then
	echo "tokens.csv 가 없습니다. 앱 서버에서 회원을 시드하고 토큰을 만들어 여기로 옮기세요." >&2
	exit 1
fi

# 토큰은 1시간짜리다. 만료된 파일로 재면 5만 건이 전부 401 이 되어 회차가 통째로 날아간다.
EXP=$(head -1 tokens.csv | cut -d, -f2 | cut -d. -f2 \
	| tr '_-' '/+' | base64 -d 2>/dev/null | grep -oE '"exp":[0-9]+' | grep -oE '[0-9]+' || echo 0)
NOW=$(date +%s)
if [ "$EXP" -le "$NOW" ]; then
	echo "토큰이 만료됐습니다. generate-tokens.mjs 로 새로 뽑아 옮기세요." >&2
	exit 1
fi
echo "[${LABEL}] 토큰 만료까지 $(( (EXP - NOW) / 60 ))분"

if ! curl -sf --max-time 5 "http://${APP_HOST}:${MGMT_PORT}/actuator/health" >/dev/null; then
	echo "앱 actuator(${APP_HOST}:${MGMT_PORT})에 붙지 못했습니다. 보안 그룹을 확인하세요." >&2
	exit 1
fi

echo "[${LABEL}] ${BASE_URL} 에 ${RATE}/s × ${DURATION}, 재고 ${STOCK}장"

# actuator 는 건너편이라 URL 로 받는다. 1초 간격 — 기본 스크랩 15초로는 10초 스파이크를 통째로 놓친다.
# coupon_issue_rejected_total 을 반드시 포함한다 — CouponSoldOutFilter 가 응답을 끝내면
# http_server_requests 에 안 잡히므로, 빠뜨리면 거절 건수를 관측할 수단이 사라진다.
(
	: >"$METRICS"
	while true; do
		TS=$(date +%s)
		curl -s --max-time 1 "http://${APP_HOST}:${MGMT_PORT}/actuator/prometheus" \
			| grep -E '^(hikaricp_connections_(active|idle|pending|timeout_total)|tomcat_threads_(busy|current)|jvm_threads_live|http_server_requests_seconds_(count|sum)|process_cpu_usage|system_cpu_usage|coupon_issue_rejected_total)' \
			| sed "s/^/${TS} /" >>"$METRICS" || true
		sleep 1
	done
) &
COLLECTOR=$!
trap 'kill "$COLLECTOR" 2>/dev/null || true' EXIT

BASE_URL="$BASE_URL" RATE="$RATE" DURATION="$DURATION" STOCK="$STOCK" \
	PERF_KEY="${PERF_KEY:-}" COUPON_ID="${COUPON_ID:-}" ALLOW_STAGING="${ALLOW_STAGING:-}" \
	k6 run --out "csv=${RESULT}" coupon-spike.js || true

kill "$COLLECTOR" 2>/dev/null || true

echo ""
echo "=== 결과 요약 ==="
./summarize.sh "$RESULT" "$METRICS"

echo ""
echo "정합성은 앱 서버에서 확인하세요:"
echo "  sudo docker exec staging-postgres psql -U <user> -d soboro -c \\"
echo "    \"SELECT c.total_quantity 재고, count(i.id) 발급행, c.issued_quantity 카운터,"
echo "            count(*) FILTER (WHERE false) 중복"
echo "       FROM coupons c LEFT JOIN coupon_issues i ON i.coupon_id = c.id"
echo "      WHERE c.name = '[부하측정] 선착순 쿠폰' GROUP BY 1,3;\""
echo ""
echo "산출물: $RESULT · $METRICS"
