#!/usr/bin/env bash
# 부하 중 초안 Redis 마스터를 죽여 Sentinel 승격을 일으킨다. 승격 시간은 k6 의 first_success_after_failure_ms 로 본다.
#
#   scripts/lab/draft-buffer/kill-master.sh            # 지금 마스터를 kill (SIGKILL — 정상 종료 아님)
#   scripts/lab/draft-buffer/kill-master.sh --revive   # 죽인 노드를 다시 띄운다(레플리카로 합류)
set -euo pipefail

SENTINEL_PORT="${SENTINEL_PORT:-26379}"

current_master() {
	redis-cli -p "${SENTINEL_PORT}" SENTINEL get-master-addr-by-name draft | head -1
}

if [ "${1:-}" = "--revive" ]; then
	docker compose -f docker-compose.draft-ha.yml start redis-draft-master redis-draft-replica
	sleep 2
	echo "지금 마스터: $(current_master)"
	exit 0
fi

MASTER_HOST="$(current_master)"
case "${MASTER_HOST}" in
	redis-draft-master|redis-draft-replica) ;;
	*) echo "Sentinel 이 알려준 마스터가 예상 밖이다: ${MASTER_HOST}" >&2; exit 1 ;;
esac

echo "$(date +%T.%3N) kill ${MASTER_HOST}"
docker kill --signal=SIGKILL "${MASTER_HOST}" >/dev/null
START=$(date +%s%3N)
while true; do
	NEW="$(current_master 2>/dev/null || true)"
	if [ -n "${NEW}" ] && [ "${NEW}" != "${MASTER_HOST}" ]; then
		echo "$(date +%T.%3N) 승격 완료 → ${NEW} ($(( $(date +%s%3N) - START ))ms)"
		break
	fi
	sleep 0.2
done
