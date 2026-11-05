#!/bin/bash
# 배치 이미지 진입점. EC2 의 bbang-batch(runner.py)가 아래 명령 중 하나로 컨테이너를 띄운다.
#   job <이름>                 WAS 배치 작업 하나 (예: ranking-snapshot) — 끝나면 종료 코드로 성공·실패를 알린다
#   dataops-nightly [시간] [최대가게수]     dataops 서버를 띄워 제한 수집을 돌리고 서버를 내린다
#   dataops-cycle [옵션]       갱신·공공데이터 신규/폐업·네이버 검색을 독립 트랙으로 실행
#   dataops-server             dataops 판정 서버만 띄운다 (사람이 승인 스크립트를 따로 돌릴 때)
#   dataops-migrate            별도 dataops DB의 Flyway만 실행한다 (수집·운영 반영 없음)
#   dataops <스크립트> [인자]  dataops/scripts 의 스크립트 하나 (승인·반영 등 수동 작업)
set -euo pipefail

DATAOPS_PORT=8090
DATAOPS_SERVER="http://127.0.0.1:${DATAOPS_PORT}"

# Java 용 JDBC URL 을 Python 접속 변수로 푼다
eval "$(python3 /app/jdbc_env.py)"

start_dataops_server() {
	java -Dloader.main=sweetgirl.dataops.DataopsApplication -cp /app/batch.jar \
		org.springframework.boot.loader.launch.PropertiesLauncher \
		--spring.profiles.active=prod --server.port="${DATAOPS_PORT}" &
	DATAOPS_PID=$!
	# 별도 마이그레이션 후 스키마 검증·서버 준비를 기다린다 — 최대 5분
	for _ in $(seq 1 60); do
		if python3 -c "import json,sys,urllib.request as u; sys.exit(json.load(u.urlopen('${DATAOPS_SERVER}/actuator/health', timeout=3)).get('status') != 'UP')" 2>/dev/null; then
			return 0
		fi
		if ! kill -0 "${DATAOPS_PID}" 2>/dev/null; then
			echo "dataops 서버가 기동 중 종료됐다" >&2
			wait "${DATAOPS_PID}" || true
			return 1
		fi
		sleep 5
	done
	echo "dataops 서버가 5분 안에 UP 이 되지 않았다" >&2
	return 1
}

stop_dataops_server() {
	if [ -n "${WORKER_PID:-}" ] && kill -0 "${WORKER_PID}" 2>/dev/null; then
		kill -TERM "${WORKER_PID}"
		wait "${WORKER_PID}" || true
	fi
	if [ -n "${DATAOPS_PID:-}" ] && kill -0 "${DATAOPS_PID}" 2>/dev/null; then
		kill "${DATAOPS_PID}"
		wait "${DATAOPS_PID}" || true
	fi
}

command="${1:-}"
shift || true
case "${command}" in
	dataops-migrate)
		[ "$#" -eq 0 ] || { echo "dataops-migrate는 추가 인자를 받지 않는다" >&2; exit 64; }
		exec java -Dloader.main=sweetgirl.dataops.migration.DataopsMigrationMain -cp /app/batch.jar \
			org.springframework.boot.loader.launch.PropertiesLauncher
		;;
	job)
		[ -n "${1:-}" ] || { echo "작업 이름이 필요하다: job <이름>" >&2; exit 64; }
		exec java -jar /app/batch.jar --batch.job="$1"
		;;
	dataops-server)
		exec java -Dloader.main=sweetgirl.dataops.DataopsApplication -cp /app/batch.jar \
			org.springframework.boot.loader.launch.PropertiesLauncher \
			--spring.profiles.active=prod --server.port="${DATAOPS_PORT}"
		;;
	dataops-nightly)
		[ "$#" -le 2 ] || { echo "사용: dataops-nightly [시간] [최대가게수]" >&2; exit 64; }
		hours="${1:-6}"
		worker_script=scripts/weekly_refresh.py
		worker_args=(nightly --crawl-hours "${hours}" --server "${DATAOPS_SERVER}")
		if [ "${DATAOPS_FULL_CYCLE_ENABLED:-false}" = true ]; then
			# 공공데이터·검색 트랙의 실행 시간을 남긴다. 기존 nightly 기본 6시간은 유지한다.
			hours="${1:-0.25}"
			worker_script=scripts/freshness_cycle.py
			worker_args=(run --crawl-hours "${hours}" --server "${DATAOPS_SERVER}")
		fi
		if [ "$#" -eq 2 ]; then
			[[ "$2" =~ ^[1-9][0-9]*$ ]] || { echo "최대가게수는 양의 정수여야 한다" >&2; exit 64; }
			if [ "${DATAOPS_FULL_CYCLE_ENABLED:-false}" = true ]; then
				worker_args+=(--max-items "$2")
			else
				worker_args+=(--max-stores "$2")
			fi
		fi
		trap stop_dataops_server EXIT
		trap 'exit 143' TERM
		trap 'exit 130' INT
		start_dataops_server
		cd /app/dataops
		# 쓰기 계정 서버를 켜 둔 채 두지 않는다 — 수집만 하고 끝나면 trap 이 서버를 내린다
		python3 "${worker_script}" "${worker_args[@]}" &
		WORKER_PID=$!
		wait "${WORKER_PID}"
		;;
	dataops-cycle)
		if [ "${1:-}" = plan ] || [ "${1:-}" = baseline ] || [ "${1:-}" = start-cycle ]; then
			cd /app/dataops
			exec python3 scripts/freshness_cycle.py "$@"
		fi
		trap stop_dataops_server EXIT
		trap 'exit 143' TERM
		trap 'exit 130' INT
		start_dataops_server
		cd /app/dataops
		python3 scripts/freshness_cycle.py "$@" &
		WORKER_PID=$!
		wait "${WORKER_PID}"
		;;
	dataops)
		[ -n "${1:-}" ] || { echo "스크립트 이름이 필요하다: dataops <스크립트> [인자]" >&2; exit 64; }
		script="$1"
		shift
		[[ "${script}" =~ ^[a-z0-9_]+\.py$ ]] || { echo "허용하지 않는 스크립트 이름: ${script}" >&2; exit 64; }
		cd /app/dataops
		exec python3 "scripts/${script}" "$@"
		;;
	*)
		echo "사용: job <이름> | dataops-nightly [시간] [최대가게수] | dataops-cycle [옵션] | dataops-server | dataops-migrate | dataops <스크립트> [인자]" >&2
		exit 64
		;;
esac
