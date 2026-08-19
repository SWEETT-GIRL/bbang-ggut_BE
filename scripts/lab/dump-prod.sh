#!/usr/bin/env bash
# 운영 RDS를 로컬 랩 PG로 옮기기 위한 덤프.
# - 이 맥에는 pg_dump 가 없으므로 postgres:18 컨테이너의 pg_dump 를 쓴다.
# - 접속 정보는 application-local.properties 의 spring.datasource.* (SSH 터널 localhost:15432) 를 그대로 읽는다.
# - request_log(약 400MB, 검색 실험과 무관) 데이터는 제외한다.
# - 결과는 저장소 밖 ~/bbanggut-lab/dumps 에 남긴다 (운영 데이터가 git 에 들어가지 않게).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROPS="${PROPS:-$REPO_ROOT/src/main/resources/application-local.properties}"
OUT_DIR="${LAB_DUMP_DIR:-$HOME/bbanggut-lab/dumps}"
mkdir -p "$OUT_DIR"

read_prop() { grep "^$1=" "$PROPS" | head -1 | cut -d= -f2- ; }

JDBC_URL="$(read_prop spring.datasource.url)"          # jdbc:postgresql://localhost:15432/soboro
DB_USER="$(read_prop spring.datasource.username)"
DB_PASS="$(read_prop spring.datasource.password)"
HOST_PORT_DB="${JDBC_URL#jdbc:postgresql://}"           # localhost:15432/soboro
DB_HOST="${HOST_PORT_DB%%:*}"
DB_PORT="${HOST_PORT_DB#*:}"; DB_PORT="${DB_PORT%%/*}"
DB_NAME="${HOST_PORT_DB##*/}"; DB_NAME="${DB_NAME%%\?*}"

# 컨테이너에서 맥의 localhost 터널로 나가려면 host.docker.internal 을 써야 한다.
if [ "$DB_HOST" = "localhost" ] || [ "$DB_HOST" = "127.0.0.1" ]; then
	DB_HOST="host.docker.internal"
fi

STAMP="$(date +%Y%m%d-%H%M)"
OUT_FILE="soboro-${STAMP}.dump"

echo "[dump] ${DB_HOST}:${DB_PORT}/${DB_NAME} (user=${DB_USER}) -> ${OUT_DIR}/${OUT_FILE}"
docker run --rm \
	-e PGPASSWORD="$DB_PASS" \
	-v "$OUT_DIR":/dump \
	postgres:18 \
	pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
		--format=custom --compress=3 --no-owner --no-privileges \
		--exclude-table-data=request_log \
		--file="/dump/${OUT_FILE}"

ls -lh "$OUT_DIR/$OUT_FILE"
echo "[dump] done: $OUT_DIR/$OUT_FILE"
