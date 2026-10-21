#!/usr/bin/env bash
# dataops 로컬 DB 주기 백업 — apply_log·provenance 는 유실 시 복구 불가라 이 백업이 유일 안전장치다.
# place_snapshot 은 제외한다 (부피 크고 재수집 가능). 3단계 소형 테이블 RDS 승격 시 이 스크립트는 은퇴.
# 원격 보관(플랜 P-5)이 정해지면 마지막 단계에 업로드를 추가할 것.
set -euo pipefail

BACKUP_DIR="$(dirname "$0")/../backups"
mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d_%H%M%S)"
TARGET="$BACKUP_DIR/dataops_${STAMP}.sql.gz"

docker exec bbanggut-dataops-db pg_dump -U dataops -d dataops \
	--exclude-table-data=place_snapshot \
	| gzip > "$TARGET"

echo "백업 완료: $TARGET ($(du -h "$TARGET" | cut -f1))"
