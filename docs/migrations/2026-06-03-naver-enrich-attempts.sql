-- 2026-06-03 — store 에 naver_enrich_attempts 컬럼 추가 (미발견 가게 파킹)
-- 적용 대상: dev DB (soboro @ 3.27.115.144)
-- 사용처: 네이버 검색이 '진짜 무결과(폐업/오등록/빵집 아님)'로 반복 실패하는 가게를
--        무한 재시도(7일 throttle 후 큐 재진입) 하지 않도록 시도 횟수를 누적.
--        SEARCH_EMPTY(후보 0건) 실패에서만 +1 — GATE_REJECT(후보 있음)는 수동 후보선택
--        대상이라 카운트 안 함. attempts >= 3 이면 enrich 큐에서 제외(파킹).
--        어드민의 retry / placeId 초기화 시 0 으로 리셋되어 다시 큐 진입.
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-06-03-naver-enrich-attempts.sql
--   또는: scripts/.venv/bin/python scripts/apply_migration.py docs/migrations/2026-06-03-naver-enrich-attempts.sql
--
-- 적용 후 backend 재기동 — local 프로파일 ddl-auto=validate 통과 확인.

ALTER TABLE store
    ADD COLUMN IF NOT EXISTS naver_enrich_attempts INTEGER NOT NULL DEFAULT 0;
