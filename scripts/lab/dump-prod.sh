#!/usr/bin/env bash
# 운영 RDS를 로컬 랩 PG로 옮기기 위한 덤프.
# - 이 맥에는 pg_dump 가 없으므로 postgres:18 컨테이너의 pg_dump 를 쓴다.
# - 접속 정보는 application-local.properties 의 spring.datasource.* (SSH 터널 localhost:15432) 를 그대로 읽는다.
# - 결과는 저장소 밖 ~/bbanggut-lab/dumps 에 남긴다 (운영 데이터가 git 에 들어가지 않게).
#
# 개인정보 원칙: 검색 실험에는 가게·메뉴·지역 데이터면 충분하다. 기본은 회원·인증·알림·신고·문의 등
# 개인정보/민감 테이블의 **데이터를 제외**하고 스키마만 가져온다(FK·마이그레이션 검증은 되고 검색 실험도 된다).
# 전체 데이터가 정말 필요할 때만 FULL_DUMP=1 로 명시한다. 덤프 파일은 실험이 끝나면 지운다(~/bbanggut-lab/dumps).
set -euo pipefail

# 데이터 제외 테이블 (스키마는 포함). request_log 는 용량(400MB), 나머지는 개인정보·민감 정보.
PII_TABLES=(
	request_log
	member oauth refresh_token fcm_token notification_setting member_block member_restriction
	member_badges member_missions marketing_notification store_notification report_notification
	inquiry abuse_report moderation_action moderation_appeal
	contribution_report store_contribution_report store_contribution_report_image_file
	menu_contribution_report menu_contribution_report_image_file
	review review_image_file review_recommend review_texture_tag
	bread_record bread_record_photo bread_record_tag bread_record_loved_bread
	saved_stores saved_courses subscribed_store course_progress liked_menu_category menu_like search_misses
)

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

EXCLUDE_ARGS=()
if [ "${FULL_DUMP:-0}" = "1" ]; then
	echo "[dump] FULL_DUMP=1 — 개인정보 테이블 데이터까지 포함합니다. 실험 후 반드시 삭제하세요."
	EXCLUDE_ARGS+=(--exclude-table-data=request_log)
else
	for t in "${PII_TABLES[@]}"; do
		EXCLUDE_ARGS+=(--exclude-table-data="$t")
	done
	echo "[dump] 개인정보 테이블 ${#PII_TABLES[@]}개는 스키마만 (데이터 제외). 전체가 필요하면 FULL_DUMP=1"
fi

echo "[dump] ${DB_HOST}:${DB_PORT}/${DB_NAME} (user=${DB_USER}) -> ${OUT_DIR}/${OUT_FILE}"
docker run --rm \
	-e PGPASSWORD="$DB_PASS" \
	-v "$OUT_DIR":/dump \
	postgres:18 \
	pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
		--format=custom --compress=3 --no-owner --no-privileges \
		"${EXCLUDE_ARGS[@]}" \
		--file="/dump/${OUT_FILE}"

ls -lh "$OUT_DIR/$OUT_FILE"
echo "[dump] done: $OUT_DIR/$OUT_FILE"
