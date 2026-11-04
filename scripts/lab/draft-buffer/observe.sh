#!/usr/bin/env bash
# 측정 중 서버 쪽 숫자를 1초마다 적는다 — 왜 깨졌는지의 원인 증거.
#   DB 락 대기 수(pg_stat_activity wait_event_type = Lock) · Hikari pending · 초안 지표(backlog·write·flush·lag).
#
#   scripts/lab/draft-buffer/observe.sh results/<날짜>-buffered/server.log
#   (Ctrl-C 로 멈춘다)
set -euo pipefail

OUT="${1:?사용: observe.sh <출력파일>}"
MGMT_PORT="${MGMT_PORT:-8091}"
PG="docker exec postgres-lab psql -U sweetgirl -d soboro -At -c"

mkdir -p "$(dirname "${OUT}")"
echo "time,lock_waits,active_backends,hikari_pending,hikari_active,draft_backlog,draft_write_accepted,draft_write_stale,draft_flush_total,draft_flush_batch_sum,draft_lag_p95" >> "${OUT}"

metric() {
	grep "^$1" "$2" | awk '{s+=$NF} END {print s+0}'
}

while true; do
	PROM="$(curl -s --max-time 2 "http://localhost:${MGMT_PORT}/actuator/prometheus" || true)"
	LOCKS="$(${PG} "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'" 2>/dev/null || echo "?")"
	ACTIVE="$(${PG} "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND datname = 'soboro'" 2>/dev/null || echo "?")"
	echo "$(date +%T),${LOCKS},${ACTIVE},$(metric 'hikaricp_connections_pending' <<<"${PROM}"),$(metric 'hikaricp_connections_active' <<<"${PROM}"),$(metric 'draft_backlog' <<<"${PROM}"),$(grep '^draft_write_total{' <<<"${PROM}" | grep accepted | awk '{print $NF+0}'),$(grep '^draft_write_total{' <<<"${PROM}" | grep stale | awk '{print $NF+0}'),$(metric 'draft_flush_total' <<<"${PROM}"),$(metric 'draft_flush_batch_size_sum' <<<"${PROM}"),$(grep '^draft_flush_lag_seconds{' <<<"${PROM}" | grep 'quantile="0.95"' | awk '{print $NF+0}')" >> "${OUT}"
	sleep 1
done
