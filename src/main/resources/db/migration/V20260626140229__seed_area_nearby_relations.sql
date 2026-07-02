-- AREA(STATION/HOT_PLACE/LANDMARK) 간 NEARBY relation 자동생성
-- 선행: V20260626100000(STATION 시드) 적용 필수.
--
-- 정책 (조합 폭발·중복 방지):
--  · 좌표 있는 AREA끼리만 (CITY/GU/DONG는 center 좌표 없음 → 대상 아님)
--  · 서로 다른 타입만 (a.type <> b.type): 역↔핫플, 역↔랜드마크, 핫플↔랜드마크.
--    같은 타입(역↔역 등)은 검색 의미가 약해 제외.
--  · 단방향 정규화 (a.id < b.id): NEARBY는 대칭이라 한 쌍당 1 row. 검색은 from/to 양방향 조회.
--  · center 거리 800m 이내(도보권). weight = 거리 감쇠(0m≈1.0, 800m≈0.6).
-- 재실행 안전(ON CONFLICT DO NOTHING).

WITH area AS (
    SELECT id, type,
        ST_SetSRID(ST_MakePoint(center_lng, center_lat), 4326)::geography AS g
    FROM search_region
    WHERE level = 'AREA' AND center_lat IS NOT NULL AND center_lng IS NOT NULL AND is_active = TRUE
)
INSERT INTO search_region_relation (from_region_id, to_region_id, relation_type, weight)
SELECT a.id, b.id, 'NEARBY',
    ROUND((1 - ST_Distance(a.g, b.g) / 800.0 * 0.4)::numeric, 2)
FROM area a
JOIN area b
    ON a.id < b.id
    AND a.type <> b.type
    AND ST_DWithin(a.g, b.g, 800)
ON CONFLICT ON CONSTRAINT uq_search_region_relation DO NOTHING;
