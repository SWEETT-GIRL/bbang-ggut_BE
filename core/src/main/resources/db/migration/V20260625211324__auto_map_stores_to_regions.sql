-- 가게 → 검색지역 자동 매핑 (store_search_region)
-- AREA(HOT_PLACE/STATION/LANDMARK)는 PostGIS 반경, 행정구역(CITY/GU)은 텍스트 매핑.
-- 전부 mapping_type='AUTO', is_primary=FALSE. 재실행 안전(ON CONFLICT DO NOTHING).
-- 신규 가게는 인덱싱 파이프라인에서 별도 처리(다음 단계). 본 마이그레이션은 기존 가게 일괄 백필.

-- 1) AREA — PostGIS ST_DWithin (좌표 반경 내 핫플/역/랜드마크)
INSERT INTO store_search_region (store_id, region_id, weight, mapping_type, is_primary)
SELECT s.id, r.id,
    CASE r.type
        WHEN 'HOT_PLACE' THEN 1.00
        WHEN 'STATION'   THEN 0.80
        WHEN 'LANDMARK'  THEN 0.70
    END,
    'AUTO', FALSE
FROM store s
JOIN search_region r
    ON r.level = 'AREA'
    AND r.center_lat IS NOT NULL
    AND r.center_lng IS NOT NULL
    AND r.radius_meters IS NOT NULL
    AND r.is_active = TRUE
    AND ST_DWithin(
        s.location::geography,
        ST_SetSRID(ST_MakePoint(r.center_lng, r.center_lat), 4326)::geography,
        r.radius_meters
    )
WHERE s.status = 'ACTIVE'
ON CONFLICT ON CONSTRAINT uq_store_search_region DO NOTHING;

-- 2) 행정구역 — 텍스트 매핑 (CITY: region_1st alias, GU: region_2nd 이름 + parent CITY)
INSERT INTO store_search_region (store_id, region_id, weight, mapping_type, is_primary)
SELECT m.store_id, m.region_id, m.weight, 'AUTO', FALSE
FROM (
    -- CITY: store.region_1st 를 CITY alias로 매칭 (단축형/풀네임 모두)
    SELECT s.id AS store_id, city.id AS region_id, 0.30 AS weight
    FROM store s
    JOIN search_region_alias ca ON ca.alias = s.region_1st
    JOIN search_region city ON city.id = ca.region_id AND city.level = 'CITY' AND city.is_active = TRUE
    WHERE s.status = 'ACTIVE' AND s.region_1st IS NOT NULL AND s.region_1st <> ''

    UNION ALL

    -- GU: store.region_2nd 이름 + parent=가게의 CITY (동음이의 "중구" 등 정확 구분)
    SELECT s.id, gu.id, 0.50
    FROM store s
    JOIN search_region_alias ca ON ca.alias = s.region_1st
    JOIN search_region city ON city.id = ca.region_id AND city.level = 'CITY'
    JOIN search_region gu ON gu.name = s.region_2nd
        AND gu.parent_region_id = city.id AND gu.level = 'GU' AND gu.is_active = TRUE
    WHERE s.status = 'ACTIVE' AND s.region_2nd IS NOT NULL AND s.region_2nd <> ''
) m
ON CONFLICT ON CONSTRAINT uq_store_search_region DO NOTHING;
