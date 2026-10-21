-- 가게 → 검색지역(DONG) 자동 매핑 (store_search_region)
-- V20260625211324(AREA/CITY/GU 매핑) + V20260625214853(DONG 시드) 이후 적용.
-- store.region_3rd(법정동)를 DONG search_region에 연결. parent 체인으로 동음이의 정확 구분.
--
-- 매핑 조건: region_1st→CITY(alias), region_2nd→GU(이름+parent), region_3rd→DONG(이름+parent).
-- 행정동/도로명/특례시 구박힘 등 법정동과 다른 region_3rd는 매핑되지 않음(별도 데이터 정정 필요).
-- 재실행 안전(ON CONFLICT DO NOTHING).

INSERT INTO store_search_region (store_id, region_id, weight, mapping_type, is_primary)
SELECT s.id, d.id, 0.90, 'AUTO', FALSE
FROM store s
JOIN search_region_alias ca ON ca.alias = s.region_1st
JOIN search_region city ON city.id = ca.region_id AND city.level = 'CITY'
JOIN search_region gu ON gu.name = s.region_2nd
    AND gu.parent_region_id = city.id AND gu.level = 'GU'
JOIN search_region d ON d.name = s.region_3rd
    AND d.parent_region_id = gu.id AND d.level = 'DONG' AND d.is_active = TRUE
WHERE s.status = 'ACTIVE'
  AND s.region_3rd IS NOT NULL AND s.region_3rd <> ''
ON CONFLICT ON CONSTRAINT uq_store_search_region DO NOTHING;
