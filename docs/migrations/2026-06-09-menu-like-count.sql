-- menu.like_count 컬럼 추가
--
-- 적용:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-06-09-menu-like-count.sql
--
-- Hibernate validate 에러:
--   Schema validation: missing column [like_count] in table [menu]

ALTER TABLE menu
    ADD COLUMN IF NOT EXISTS like_count INTEGER;

UPDATE menu
SET like_count = 0
WHERE like_count IS NULL;

ALTER TABLE menu
    ALTER COLUMN like_count SET DEFAULT 0,
    ALTER COLUMN like_count SET NOT NULL;
