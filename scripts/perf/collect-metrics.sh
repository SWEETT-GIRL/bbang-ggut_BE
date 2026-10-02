#!/usr/bin/env bash
# 측정 중 서버 안쪽 지표를 1초 간격으로 긁는다.
#
#   scripts/perf/collect-metrics.sh [출력파일] [관리포트]
#
# k6 는 "밖에서 본 증상"(응답 코드·지연)만 본다. 500 이 풀 고갈 때문인지 락 타임아웃 때문인지는
# 여기서 나온다. k6 CSV 와 이 로그를 **같은 타임스탬프로 맞춰** 보는 것이 이 측정의 핵심이다.
#
# 정식 Prometheus 를 세우지 않는 이유: 기본 스크랩 간격 15초는 10초짜리 스파이크를 통째로 놓친다.
set -euo pipefail

OUT="${1:-scripts/perf/server-metrics.log}"
PORT="${2:-8081}"
# http_server_requests_seconds = 서버가 직접 재는 처리 시간(큐 대기 제외).
# k6 가 재는 시간과 비교하면 "처리가 느린 것"과 "차례를 기다린 것"이 갈린다 — 코드를 볼지 인프라를 볼지가 여기서 결정된다.
PATTERN='hikaricp_connections_(active|idle|pending|timeout_total)|tomcat_threads_(busy|current)|jvm_threads_live|http_server_requests_seconds_(count|sum)|process_cpu_usage|system_cpu_usage'

if ! curl -sf --max-time 2 "http://localhost:${PORT}/actuator/prometheus" >/dev/null; then
	echo "actuator(localhost:${PORT})에 붙지 못했습니다. 앱이 떠 있는지 확인하세요." >&2
	exit 1
fi

echo "[수집] ${OUT} 에 1초 간격으로 기록합니다. Ctrl+C 로 중단." >&2
: >"$OUT"
while true; do
	TS=$(date +%s)
	curl -s --max-time 1 "http://localhost:${PORT}/actuator/prometheus" \
		| grep -E "^($PATTERN)" \
		| sed "s/^/${TS} /" >>"$OUT" || true
	sleep 1
done
