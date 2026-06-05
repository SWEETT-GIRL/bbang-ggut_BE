-- 2026-05-29 — 크롤러 중복 방지용 UNIQUE 제약 추가
-- 적용 대상: dev DB (soboro @ 3.27.115.144)
--
-- 배경: 크롤러 재실행/동시실행 시 자연키 기준 DB 제약이 없어 사진·메뉴가 중복 insert 됐다.
--   - 사진: 같은 (target, source_url) 가 레이스로 2번 들어감
--   - 메뉴: 같은 (store, 정규화이름) 이 재크롤로 2번 들어감
-- 애플리케이션 멱등성(읽고-쓰기)만으로는 동시성 레이스를 못 막으므로 DB UNIQUE 로 backstop.
--
-- ⚠️ 반드시 데이터 정리(중복 제거) 후 적용할 것 — 기존 중복이 남아 있으면 인덱스 생성이 실패한다.
--    정리 스크립트: scripts/dedup_menus.py, scripts/dedup_store_photos.py
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-05-29-dedup-unique-constraints.sql
-- 적용 후 backend 재기동 — local 프로파일 ddl-auto=validate 통과 확인.

-- 기존 non-unique 인덱스(이전 마이그레이션) 제거 — UNIQUE 가 조회도 커버하므로 중복.
DROP INDEX IF EXISTS idx_store_image_file_store_source_url;
DROP INDEX IF EXISTS idx_menu_image_file_menu_source_url;

-- 사진: 같은 가게/메뉴에 같은 원본 URL(canonical) 이 두 번 들어가지 못하게.
-- partial — 어드민 웹 직접 업로드는 source_url 이 null 이라 제외(여러 장 허용).
CREATE UNIQUE INDEX IF NOT EXISTS uq_store_image_file_store_source_url
    ON store_image_file (store_id, source_url)
    WHERE source_url IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_image_file_menu_source_url
    ON menu_image_file (menu_id, source_url)
    WHERE source_url IS NOT NULL;

-- 메뉴: 같은 가게에 정규화 이름이 같은 활성 메뉴가 두 개 들어가지 못하게.
-- 정규화식은 Java MenuNameNormalizer 와 동일(공백·특수문자 제거 + lowercase, 한글/영문/숫자 유지).
--   검증: 이 식 기준 기존 활성메뉴 중복 그룹 수 == Java 계산값(844) 으로 일치 확인 완료.
-- partial 조건:
--   · deleted_at IS NULL — soft-delete 메뉴 제외(지운 뒤 같은 이름 재등록 허용)
--   · created_at >= '2026-05-27' — 그 이전은 팀원 수기 데이터로 정리 대상에서 제외했고,
--     수기 중복 37그룹이 남아 있어 인덱스를 막으므로 스코프로 제외. 신규 insert 는 created_at 이
--     항상 이 이후라 사실상 모든 크롤/신규 메뉴에 유니크가 강제됨(동시성 레이스 backstop).
CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_store_normalized_name
    ON menu (store_id, lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g')))
    WHERE deleted_at IS NULL AND created_at >= '2026-05-27';
