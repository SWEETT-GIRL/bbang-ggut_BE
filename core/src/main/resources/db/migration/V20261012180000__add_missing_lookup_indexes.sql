-- Flyway 도입 전에 만들어진 표라 조회 컬럼의 색인이 빠져 있었다. 운영에서 표를 통째로 읽고 있던 조회들이다.
-- 2026-10-12 운영 pg_stat_statements(8/18 이후) 기준, 아래 조회들이 DB 실행 시간 55,972초 중 54,593초(97.5%)였다.

-- 가게 상세: 가게의 영업시간 (store_id 로 찾고 요일 순 정렬)
CREATE INDEX IF NOT EXISTS idx_store_business_hour_store_day
    ON store_business_hour (store_id, day_of_week);

-- 가게 상세·메뉴 목록: 메뉴의 사진 (menu_id 로 찾고 노출 순 정렬)
CREATE INDEX IF NOT EXISTS idx_menu_image_file_menu_order
    ON menu_image_file (menu_id, display_order, id);

-- 이미지 처리 콜백: 사진 키로 행 찾기
CREATE INDEX IF NOT EXISTS idx_menu_image_file_aws_key
    ON menu_image_file (aws_key);

CREATE INDEX IF NOT EXISTS idx_store_image_file_aws_key
    ON store_image_file (aws_key);

CREATE INDEX IF NOT EXISTS idx_bread_record_photo_aws_key
    ON bread_record_photo (aws_key);

-- 검색: 검색어의 지역 별칭 판정
CREATE INDEX IF NOT EXISTS idx_search_region_alias_normalized_alias
    ON search_region_alias (normalized_alias);

-- 리뷰의 식감 태그 (기본 키도 색인도 없는 컬렉션 표 — 리뷰를 읽을 때 review_id 로 찾는다)
CREATE INDEX IF NOT EXISTS idx_review_texture_tag_review
    ON review_texture_tag (review_id);

-- 1분마다 도는 미처리 이미지 집계 (processing_status = 'PENDING' 을 세려고 큰 표 둘을 통째로 읽었다, 평균 66.6ms)
CREATE INDEX IF NOT EXISTS idx_menu_image_file_pending
    ON menu_image_file (id) WHERE processing_status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_store_image_file_pending
    ON store_image_file (id) WHERE processing_status = 'PENDING';

-- ddl-auto 시절 Hibernate 가 자동 이름으로 한 번 더 만든 중복 (같은 컬럼 구성의 이름 있는 색인·제약이 따로 있다).
-- 자동 이름은 운영에만 있을 수 있어 IF EXISTS 로 지운다.
DROP INDEX IF EXISTS idxgk16asr6q91uroyqevpefruh8;
ALTER TABLE naver_enrich_candidate DROP CONSTRAINT IF EXISTS uk8kf1omebw1th0rbqp02j0d8re;
