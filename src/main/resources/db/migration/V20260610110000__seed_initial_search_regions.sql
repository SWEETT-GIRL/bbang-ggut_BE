WITH seed_regions (name, type, level, parent_name, center_lat, center_lng, radius_meters, display_order) AS (
    VALUES
        ('서울', 'DISTRICT', 'CITY', NULL, 37.566500, 126.978000, 20000, 10),
        ('용산구', 'DISTRICT', 'GU', '서울', 37.532600, 126.990500, 5000, 20),
        ('성동구', 'DISTRICT', 'GU', '서울', 37.563300, 127.036900, 5000, 30),
        ('한남', 'HOT_PLACE', 'AREA', '용산구', 37.534500, 127.005400, 1200, 40),
        ('한남동', 'DONG', 'DONG', '용산구', 37.534500, 127.005400, 1000, 50),
        ('한강진역', 'STATION', 'AREA', '용산구', 37.539600, 127.001700, 600, 60),
        ('성수', 'HOT_PLACE', 'AREA', '성동구', 37.544600, 127.055700, 1200, 70),
        ('성수동', 'DONG', 'DONG', '성동구', 37.544600, 127.055700, 1000, 80),
        ('성수역', 'STATION', 'AREA', '성동구', 37.544600, 127.055900, 600, 90),
        ('서울숲', 'LANDMARK', 'AREA', '성동구', 37.544400, 127.037400, 800, 100)
)
INSERT INTO search_region (
    name,
    type,
    level,
    parent_region_id,
    center_lat,
    center_lng,
    radius_meters,
    display_order,
    is_active
)
SELECT
    seed.name,
    seed.type,
    seed.level,
    parent.id,
    seed.center_lat,
    seed.center_lng,
    seed.radius_meters,
    seed.display_order,
    TRUE
FROM seed_regions seed
LEFT JOIN search_region parent
    ON parent.name = seed.parent_name
WHERE NOT EXISTS (
    SELECT 1
    FROM search_region existing
    WHERE existing.name = seed.name
      AND existing.type = seed.type
);
