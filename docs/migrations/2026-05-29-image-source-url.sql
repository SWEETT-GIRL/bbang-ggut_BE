-- 2026-05-29 — StoreImageFile / MenuImageFile 에 source_url 컬럼 추가
-- 적용 대상: dev DB (soboro @ 3.27.115.144)
-- 사용처: 외부 크롤러(Python)가 다운로드한 원본 URL 을 보관해
--        같은 URL 재요청 시 중복 업로드(S3 객체/DB row)를 막기 위한 idempotency 키.
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-05-29-image-source-url.sql
--
-- 적용 후 backend 재기동 — local 프로파일 ddl-auto=validate 통과 확인.

ALTER TABLE store_image_file
    ADD COLUMN IF NOT EXISTS source_url VARCHAR(500);

ALTER TABLE menu_image_file
    ADD COLUMN IF NOT EXISTS source_url VARCHAR(500);

-- 동일 (target, source_url) 중복을 빠르게 조회하기 위한 인덱스.
-- partial index — source_url IS NOT NULL 인 row 만 (어드민 웹 업로드는 null 이라 인덱스에서 제외).
CREATE INDEX IF NOT EXISTS idx_store_image_file_store_source_url
    ON store_image_file (store_id, source_url)
    WHERE source_url IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_menu_image_file_menu_source_url
    ON menu_image_file (menu_id, source_url)
    WHERE source_url IS NOT NULL;
