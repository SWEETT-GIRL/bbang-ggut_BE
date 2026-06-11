CREATE TEMP TABLE tmp_seed_store_search_region_rule (
    region_name        VARCHAR(100) NOT NULL,
    region_type        VARCHAR(30)  NOT NULL,
    region_1st         VARCHAR(50),
    region_2nd         VARCHAR(50),
    region_3rd_pattern VARCHAR(50),
    weight             NUMERIC(4, 2) NOT NULL,
    is_primary         BOOLEAN NOT NULL
) ON COMMIT DROP;

-- MVP 초기 매핑은 MANUAL 기준으로 해석 가능한 행정동/주소 정제 데이터만 사용한다.
-- 가게 주변 역 700m 자동 매핑은 의도적으로 제외한다.
INSERT INTO tmp_seed_store_search_region_rule (
    region_name,
    region_type,
    region_1st,
    region_2nd,
    region_3rd_pattern,
    weight,
    is_primary
)
VALUES
    ('한남', 'HOT_PLACE', '서울', '용산구', '한남동', 1.00, TRUE),
    ('한남동', 'DONG', '서울', '용산구', '한남동', 0.90, FALSE),
    ('성수', 'HOT_PLACE', '서울', '성동구', '성수%', 1.00, TRUE),
    ('성수동', 'DONG', '서울', '성동구', '성수%', 0.90, FALSE),
    ('망원', 'HOT_PLACE', '서울', '마포구', '망원동', 1.00, TRUE),
    ('공덕', 'HOT_PLACE', '서울', '마포구', '공덕동', 1.00, TRUE);

INSERT INTO store_search_region (
    store_id,
    region_id,
    weight,
    mapping_type,
    is_primary
)
SELECT
    store.id,
    region.id,
    rule.weight,
    'MANUAL',
    rule.is_primary
FROM tmp_seed_store_search_region_rule rule
JOIN search_region region
    ON region.name = rule.region_name
   AND region.type = rule.region_type
JOIN store store
    ON (rule.region_1st IS NULL OR store.region_1st = rule.region_1st)
   AND (rule.region_2nd IS NULL OR store.region_2nd = rule.region_2nd)
   AND (rule.region_3rd_pattern IS NULL OR store.region_3rd LIKE rule.region_3rd_pattern)
   AND store.status = 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1
    FROM store_search_region existing
    WHERE existing.store_id = store.id
      AND existing.region_id = region.id
);
