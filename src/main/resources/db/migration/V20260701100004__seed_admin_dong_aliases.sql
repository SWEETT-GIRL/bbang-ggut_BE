-- store.region_3rd(행정동/관용 표기)를 매핑된 법정동 DONG region의 alias로 등록.
-- 목적: 검색 파서의 지역 판정을 search_region_alias로 일원화(접미사 추측 fallback 폐기)하기 위해
--       사용자가 실제 입력하는 지역 표기("성수동"·"서교동"·"삼도동")를 흡수한다.
--
-- 필터:
--  · 지역 접미사(동/읍/면/가)로 끝나는 것만 → 특례시 일반구명(팔달구 등)·지번(104-7)·숫자 배제
--  · 법정동명(d.name)과 다른 표기만 → 이미 재시드에서 등록된 정규 alias는 스킵
-- 동음이의(중앙동 등 여러 region)는 전부 등록 → 검색 시 사용자 좌표로 압축(파트 B).
-- normalized_alias는 파서 normalize(소문자 + 영숫자·한글 외 제거)와 동일 규칙.
-- 재실행 안전(ON CONFLICT DO NOTHING).

INSERT INTO search_region_alias (region_id, alias, normalized_alias, is_primary)
SELECT DISTINCT
    ssr.region_id,
    s.region_3rd,
    regexp_replace(lower(s.region_3rd), '[^0-9a-z가-힣]', '', 'g'),
    FALSE
FROM store s
JOIN store_search_region ssr ON ssr.store_id = s.id
JOIN search_region d ON d.id = ssr.region_id AND d.level = 'DONG'
WHERE s.region_3rd IS NOT NULL
  AND s.region_3rd ~ '(동|읍|면|가)$'
  AND s.region_3rd <> d.name
ON CONFLICT ON CONSTRAINT uq_search_region_alias_region_alias DO NOTHING;
