-- 가게 → STATION 검색지역 자동 매핑 (store_search_region)
-- 선행: V20260626100000(STATION 시드) 적용 필수.
-- 기존 V20260625211324(AREA 매핑)와 동일한 PostGIS ST_DWithin 패턴이나,
-- 신규 적재된 STATION 다수를 반영하기 위해 STATION type만 대상으로 재실행한다.
-- 전부 mapping_type='AUTO', is_primary=FALSE. 재실행 안전(ON CONFLICT DO NOTHING).

INSERT INTO store_search_region (store_id, region_id, weight, mapping_type, is_primary)
SELECT s.id, r.id, 0.80, 'AUTO', FALSE
FROM store s
JOIN search_region r
    ON r.type = 'STATION'
    AND r.level = 'AREA'
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
