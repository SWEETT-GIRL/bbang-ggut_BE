-- Flyway 도입 전에 만들어진 표라 조회 컬럼의 색인이 빠져 있었다. 운영에서 표를 통째로 읽고 있던 조회들이다
-- (2026-10-12 운영 실행 계획: 영업시간 27.6ms, 가게의 메뉴 사진 94.5ms, 메뉴 사진 키 조회 74.4ms).

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
