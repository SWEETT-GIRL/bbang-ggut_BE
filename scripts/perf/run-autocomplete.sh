#!/usr/bin/env bash
# 자동완성 캐시 측정 한 회차 — k6 서버에서 원격 앱을 때린다.
#
#   APP_HOST=10.3.31.174 scripts/perf/run-autocomplete.sh <회차이름> <RATE>
#
# 캐시를 켠 회차는 시작 전에 캐시를 비워 빈 상태에서 잰다(FLUSH=true, ADMIN_KEY 필요).
# 캐시 켜고 끄기는 앱 쪽 일이다 — 서버 .env 의 AUTOCOMPLETE_CACHE_ENABLED 를 바꾸고 app 만 다시 띄운다.
#
# ## 필요한 환경변수
#   APP_HOST    앱의 사설 IP                                 ※ 필수
#   MGMT_PORT   actuator 포트 (기본 8081)
#   FLUSH       true 면 시작 전 자동완성 캐시를 비운다
#   ADMIN_KEY   FLUSH 에 쓰는 관리자 키(X-Admin-Key)
set -euo pipefail

cd "$(dirname "$0")"

LABEL="${1:?사용: run-autocomplete.sh <회차이름> <RATE>}"
RATE="${2:?사용: run-autocomplete.sh <회차이름> <RATE>}"
APP_HOST="${APP_HOST:?APP_HOST 에 앱의 사설 IP 를 주세요 (예: APP_HOST=10.3.31.174)}"
APP_PORT="${APP_PORT:-8080}"
MGMT_PORT="${MGMT_PORT:-8081}"
BASE_URL="http://${APP_HOST}:${APP_PORT}"
METRICS_URL="http://${APP_HOST}:${MGMT_PORT}/actuator/prometheus"

RESULT="result-ac-${LABEL}.csv"
SUMMARY="summary-ac-${LABEL}.json"
METRICS="server-metrics-ac-${LABEL}.log"

if [ ! -f autocomplete-queries.json ]; then
	echo "autocomplete-queries.json 이 없습니다. build-autocomplete-queries.py 로 만들어 옮기세요." >&2
	exit 1
fi
if ! curl -sf --max-time 5 "http://${APP_HOST}:${MGMT_PORT}/actuator/health" >/dev/null; then
	echo "앱 actuator(${APP_HOST}:${MGMT_PORT})에 붙지 못했습니다. 보안 그룹을 확인하세요." >&2
	exit 1
fi

if [ "${FLUSH:-false}" = "true" ]; then
	echo "[${LABEL}] 자동완성 캐시를 비운다"
	curl -sf -X DELETE -H "X-Admin-Key: ${ADMIN_KEY:?FLUSH 에는 ADMIN_KEY 가 필요합니다}" \
		"${BASE_URL}/admin/search/autocomplete-cache"
	echo ""
fi

# 시작 전 지표를 찍어 두고 끝난 뒤와 빼서 이번 회차 몫만 본다
snapshot() {
	curl -s --max-time 2 "$METRICS_URL" | grep -E '^(autocomplete_cache_total|search_engine_query_seconds_(count|sum)\{[^}]*method="autocompleteStores")' || true
}
BEFORE=$(snapshot)

# 1초 간격 — 기본 스크랩 15초로는 짧은 회차를 통째로 놓친다
(
	: >"$METRICS"
	while true; do
		TS=$(date +%s)
		curl -s --max-time 1 "$METRICS_URL" \
			| grep -E '^(hikaricp_connections_(active|pending)|tomcat_threads_busy|process_cpu_usage|system_cpu_usage|autocomplete_cache_total)' \
			| sed "s/^/${TS} /" >>"$METRICS" || true
		sleep 1
	done
) &
COLLECTOR=$!
trap 'kill "$COLLECTOR" 2>/dev/null || true' EXIT

echo "[${LABEL}] ${BASE_URL} 에 ${RATE}/s — 운영 검색어 $(python3 -c 'import json;print(len(json.load(open("autocomplete-queries.json"))))')건을 한 바퀴"
BASE_URL="$BASE_URL" RATE="$RATE" k6 run --quiet --out "csv=${RESULT}" --summary-export "$SUMMARY" autocomplete-load.js || true

kill "$COLLECTOR" 2>/dev/null || true
AFTER=$(snapshot)

echo ""
echo "=== [${LABEL}] 요약 ==="
python3 - "$SUMMARY" "$METRICS" <<PY
import json, re, sys
summary = json.load(open(sys.argv[1]))['metrics']
duration = summary['http_req_duration']
failed = summary['http_req_failed']['value']
requests = summary['http_reqs']['count']
print(f"요청 {requests}건 · 실패율 {failed:.2%}")
print(f"응답(k6) p50 {duration['p(50)']:.1f}ms · p95 {duration['p(95)']:.1f}ms · p99 {duration['p(99)']:.1f}ms · max {duration['max']:.1f}ms")

def parse(text):
    values = {}
    for line in text.strip().splitlines():
        match = re.match(r'(\S+?)(\{[^}]*\})?\s+([0-9.eE+-]+)$', line)
        if match:
            values[match.group(1) + (match.group(2) or '')] = float(match.group(3))
    return values

before, after = parse('''$BEFORE'''), parse('''$AFTER''')
delta = {key: after[key] - before.get(key, 0) for key in after}
cache = {re.search(r'result="(\w+)"', key).group(1): value for key, value in delta.items() if key.startswith('autocomplete_cache_total')}
if sum(cache.values()) > 0:
    total = sum(cache.values())
    db = cache.get('miss', 0) + cache.get('error', 0)
    print(f"캐시 hit {cache.get('hit', 0):.0f} · coalesced {cache.get('coalesced', 0):.0f} · miss {cache.get('miss', 0):.0f} · error {cache.get('error', 0):.0f}")
    print(f"적중률 {(total - db) / total:.1%} · DB 까지 간 요청 {db:.0f}건")
else:
    print("캐시 지표 변화 없음 — 캐시가 꺼진 회차(모든 요청이 DB)")
engine = {key: value for key, value in delta.items() if key.startswith('search_engine_query_seconds')}
count = sum(v for k, v in engine.items() if '_count' in k)
total_seconds = sum(v for k, v in engine.items() if '_sum' in k)
if count:
    print(f"엔진 평균(서버 측, 캐시 포함) {total_seconds / count * 1000:.2f}ms")

cpu = [float(line.split()[-1]) for line in open(sys.argv[2]) if ' system_cpu_usage' in line]
if cpu:
    print(f"서버 CPU(앱+DB 호스트) 평균 {sum(cpu) / len(cpu):.0%} · 최대 {max(cpu):.0%}")
PY
echo "산출물: $RESULT · $SUMMARY · $METRICS"
