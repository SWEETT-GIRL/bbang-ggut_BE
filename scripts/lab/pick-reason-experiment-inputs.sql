-- 이유 문장 실험(#662)의 입력 고르기. 읽기만 한다.
-- 개발(스테이징) DB 터널 15433 에 붙어서 돌린다. 운영(15432)에서는 돌리지 않는다 — 실험은 스테이징에서만 한다.

-- 1) 축이 다 있는 회원 중 유형이 서로 다른 사람들. 판이 성립하려면 입력이 달라야 한다.
--    user_type · strong_store_count 로 "신호가 실제로 쌓인 회원"을 고른다. 검사만 한 회원은 축이 검사값 그대로다.
SELECT p.member_id,
       p.type_code,
       p.user_type,
       p.strong_store_count AS 강한신호가게수,
       p.version,
       round(p.axis_taste, 2)   AS 맛,
       round(p.axis_texture, 2) AS 식감,
       round(p.axis_filling, 2) AS 구성,
       round(p.axis_vibe, 2)    AS 분위기,
       cardinality(p.top_kinds) AS 종류수,
       (SELECT count(*) FROM jsonb_each(p.tags)) AS 태그수
FROM user_taste_profile p
JOIN member m ON m.id = p.member_id AND m.deleted_at IS NULL
WHERE p.axis_taste IS NOT NULL
ORDER BY p.strong_store_count DESC, p.computed_at DESC
LIMIT 20;

-- 2) 유형이 겹치지 않게 한 유형당 한 명씩.
SELECT DISTINCT ON (p.type_code) p.member_id, p.type_code, p.user_type, p.strong_store_count
FROM user_taste_profile p
JOIN member m ON m.id = p.member_id AND m.deleted_at IS NULL
WHERE p.axis_taste IS NOT NULL
ORDER BY p.type_code, p.strong_store_count DESC
LIMIT 20;

-- 3) 상권. 반경 안에 가게가 넉넉해야 4곳이 이어진다(maxHopM 안에서 못 이으면 거기서 멈춘다).
SELECT a.id                AS area_id,
       a.sido, a.name, a.region_2nd, a.region_3rd,
       a.radius_m, a.max_hop_m,
       count(s.id)         AS 반경내_가게수
FROM course_area a
LEFT JOIN store s
       ON s.deleted_at IS NULL
      AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(a.center_lng, a.center_lat), 4326)::geography, a.radius_m)
WHERE a.is_active = true
GROUP BY a.id, a.sido, a.name, a.region_2nd, a.region_3rd, a.radius_m, a.max_hop_m
HAVING count(s.id) >= 10
ORDER BY 반경내_가게수 DESC
LIMIT 20;
