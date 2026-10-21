WITH seed_aliases (region_name, region_type, alias, normalized_alias, is_primary) AS (
    VALUES
        ('서울', 'DISTRICT', '서울', '서울', TRUE),
        ('서울', 'DISTRICT', '서울시', '서울시', FALSE),
        ('서울', 'DISTRICT', '서울특별시', '서울특별시', FALSE),
        ('성수', 'HOT_PLACE', '성수', '성수', TRUE),
        ('성수', 'HOT_PLACE', '성수동', '성수동', FALSE),
        ('한강진역', 'STATION', '한강진역', '한강진역', TRUE),
        ('한강진역', 'STATION', '한강진', '한강진', FALSE),
        ('한남', 'HOT_PLACE', '한남', '한남', TRUE),
        ('한남', 'HOT_PLACE', '한남동', '한남동', FALSE)
)
INSERT INTO search_region_alias (
    region_id,
    alias,
    normalized_alias,
    is_primary
)
SELECT
    region.id,
    seed.alias,
    seed.normalized_alias,
    seed.is_primary
FROM seed_aliases seed
JOIN search_region region
    ON region.name = seed.region_name
   AND region.type = seed.region_type
WHERE NOT EXISTS (
    SELECT 1
    FROM search_region_alias existing
    WHERE existing.normalized_alias = seed.normalized_alias
);

WITH seed_relations (from_name, from_type, to_name, to_type, relation_type, weight) AS (
    VALUES
        ('서울', 'DISTRICT', '용산구', 'DISTRICT', 'CONTAINS', 1.00),
        ('용산구', 'DISTRICT', '한남동', 'DONG', 'CONTAINS', 1.00),
        ('한남', 'HOT_PLACE', '한남동', 'DONG', 'REPRESENTS', 1.00),
        ('한남', 'HOT_PLACE', '한강진역', 'STATION', 'NEARBY', 0.80),
        ('성수', 'HOT_PLACE', '성수동', 'DONG', 'REPRESENTS', 1.00),
        ('성수', 'HOT_PLACE', '성수역', 'STATION', 'NEARBY', 0.80),
        ('성수', 'HOT_PLACE', '서울숲', 'LANDMARK', 'NEARBY', 0.70)
)
INSERT INTO search_region_relation (
    from_region_id,
    to_region_id,
    relation_type,
    weight
)
SELECT
    from_region.id,
    to_region.id,
    seed.relation_type,
    seed.weight
FROM seed_relations seed
JOIN search_region from_region
    ON from_region.name = seed.from_name
   AND from_region.type = seed.from_type
JOIN search_region to_region
    ON to_region.name = seed.to_name
   AND to_region.type = seed.to_type
WHERE NOT EXISTS (
    SELECT 1
    FROM search_region_relation existing
    WHERE existing.from_region_id = from_region.id
      AND existing.to_region_id = to_region.id
      AND existing.relation_type = seed.relation_type
);
