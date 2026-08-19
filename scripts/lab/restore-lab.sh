#!/usr/bin/env bash
# 운영 덤프를 로컬 랩 PG(docker-compose.lab-postgres.yml, localhost:5432)에 복원한다.
# 사용: scripts/lab/restore-lab.sh [덤프파일명]   (생략하면 ~/bbanggut-lab/dumps 의 최신 파일)
# - postgis 는 이미지가 미리 만들어 두므로 덤프의 CREATE EXTENSION postgis / spatial_ref_sys COPY 는
#   "already exists / duplicate key" 로 건너뛴다 (pg_restore 가 경고만 내고 계속 진행).
# - 복원 후 pg_bigm, pg_trgm 확장을 만들어 검색 실험 준비를 마친다.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.lab-postgres.yml"
DUMP_DIR="${LAB_DUMP_DIR:-$HOME/bbanggut-lab/dumps}"
CONTAINER="postgres-lab"
DB_USER="sweetgirl"
DB_NAME="soboro"

DUMP_FILE="${1:-$(ls -t "$DUMP_DIR"/*.dump 2>/dev/null | head -1 | xargs -n1 basename)}"
if [ -z "${DUMP_FILE:-}" ]; then
	echo "덤프 파일이 없습니다. 먼저 scripts/lab/dump-prod.sh 를 실행하세요." >&2
	exit 1
fi

echo "[restore] compose up (build)"
docker compose -f "$COMPOSE_FILE" up -d --build

echo "[restore] PG 준비 대기"
for _ in $(seq 1 60); do
	if docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
		break
	fi
	sleep 2
done
docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME"

echo "[restore] pg_restore /dump/${DUMP_FILE} (병렬 4)"
# 확장 중복 등 무해한 오류가 섞이므로 종료 코드는 무시하고, 오류 요약만 남긴다.
set +e
docker exec "$CONTAINER" pg_restore -U "$DB_USER" -d "$DB_NAME" \
	--no-owner --no-privileges --jobs=4 --verbose "/dump/${DUMP_FILE}" 2> /tmp/pg_restore-lab.log
RESTORE_EXIT=$?
set -e
echo "[restore] pg_restore exit=${RESTORE_EXIT} (오류 로그: /tmp/pg_restore-lab.log)"
# grep -c 는 0건이면 종료코드 1 → pipefail 에 걸리지 않도록 || true 로 감싼다.
ERROR_COUNT="$(grep -c "ERROR" /tmp/pg_restore-lab.log || true)"
echo "[restore] ERROR 줄 수: ${ERROR_COUNT}"
if [ "${ERROR_COUNT}" != "0" ]; then
	grep "ERROR" /tmp/pg_restore-lab.log | grep -v "already exists" \
		| grep -v "duplicate key value violates unique constraint \"spatial_ref_sys_pkey\"" | head -20 || true
fi

echo "[restore] 검색 실험용 확장 생성"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c \
	"CREATE EXTENSION IF NOT EXISTS pg_bigm; CREATE EXTENSION IF NOT EXISTS pg_trgm; CREATE EXTENSION IF NOT EXISTS unaccent;"

echo "[restore] 검증"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
	"select extname, extversion from pg_extension order by 1;" -c \
	"select 'store' t, count(*) from store union all select 'menu', count(*) from menu union all select 'store_name_alias', count(*) from store_name_alias union all select 'search_region_alias', count(*) from search_region_alias union all select 'flyway_schema_history', count(*) from flyway_schema_history;"
echo "[restore] done — jdbc:postgresql://localhost:5432/soboro (sweetgirl / soboro-lab)"
