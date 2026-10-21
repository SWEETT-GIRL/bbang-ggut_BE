-- 이름이 겹치는 구의 지역 키에 광역시도를 붙인다.
--
-- 중구·동구·서구·남구·북구·강서구·고성군은 여러 시도에 같은 이름으로 존재하는데
-- 지역 키가 "구_동"뿐이라 구분이 안 됐다. 그 결과 famous_menu_by_region 한 행에
-- 여러 도시의 가게 랭킹이 섞여 들어갔다. 실제로 '중구_전체' 랭킹에는 서울·대구·
-- 대전·인천·부산·울산 중구 가게가 모두 들어 있었다.
--
-- 단순 rename으로는 못 고친다. 섞인 행을 가게의 region_1st 기준으로 시도별로
-- 쪼개고, 랭킹은 각 시도 안에서 다시 매긴다.

-- 1) 섞인 행을 시도별로 복제한다.
--    랭킹에 가게가 하나도 없는 행은 어느 시도인지 알 수 없어 여기서 제외하고,
--    아래 3)에서 대표 시도로 이름만 바꾼다.
CREATE TEMP TABLE region_split ON COMMIT DROP AS
SELECT DISTINCT
       f.id            AS old_id,
       f.menu_category AS menu_category,
       split_part(f.region3rd, '_', 1) AS gu,
       split_part(f.region3rd, '_', 2) AS dong,
       s.region_1st    AS sido
FROM famous_menu_by_region f
JOIN hot_store_ranking h ON h.famous_menu_by_region_id = f.id
JOIN store s             ON s.id = h.store_id
WHERE split_part(f.region3rd, '_', 1) IN ('중구','동구','서구','남구','북구','강서구','고성군')
  AND split_part(f.region3rd, '_', 3) = ''   -- 이미 시도가 붙은 행은 건드리지 않는다
  AND s.region_1st IS NOT NULL;

-- 2) 시도별 새 행을 만들고 랭킹을 옮긴다.
WITH inserted AS (
    INSERT INTO famous_menu_by_region (region3rd, menu_category)
    SELECT sido || '_' || gu || '_' || dong, menu_category
    FROM region_split
    RETURNING id, region3rd, menu_category
),
mapped AS (
    SELECT rs.old_id,
           rs.sido,
           i.id AS new_id
    FROM region_split rs
    JOIN inserted i
      ON i.region3rd = rs.sido || '_' || rs.gu || '_' || rs.dong
     AND i.menu_category = rs.menu_category
),
reranked AS (
    -- 시도별로 갈라졌으니 순위를 그 안에서 다시 1부터 매긴다.
    SELECT h.id,
           m.new_id,
           ROW_NUMBER() OVER (PARTITION BY m.new_id ORDER BY h.rank, h.id) AS new_rank
    FROM hot_store_ranking h
    JOIN store s ON s.id = h.store_id
    JOIN mapped m ON m.old_id = h.famous_menu_by_region_id AND m.sido = s.region_1st
)
UPDATE hot_store_ranking h
SET famous_menu_by_region_id = r.new_id,
    rank = r.new_rank
FROM reranked r
WHERE h.id = r.id;

-- 3) 랭킹이 하나도 없어 쪼갤 수 없던 행은 가게가 가장 많은 시도로 이름만 바꾼다.
--    (구버전 앱이 보내는 옛 이름도 Region3rd.LEGACY가 같은 시도로 보낸다 — 동작이 일치한다)
UPDATE famous_menu_by_region f
SET region3rd = CASE split_part(f.region3rd, '_', 1)
                    WHEN '중구'   THEN '서울'
                    WHEN '동구'   THEN '대구'
                    WHEN '서구'   THEN '대전'
                    WHEN '남구'   THEN '울산'
                    WHEN '북구'   THEN '대구'
                    WHEN '강서구' THEN '서울'
                    WHEN '고성군' THEN '강원'
                END || '_' || f.region3rd
WHERE split_part(f.region3rd, '_', 1) IN ('중구','동구','서구','남구','북구','강서구','고성군')
  AND split_part(f.region3rd, '_', 3) = ''
  AND NOT EXISTS (SELECT 1 FROM hot_store_ranking h WHERE h.famous_menu_by_region_id = f.id);

-- 4) 랭킹을 모두 새 행으로 옮긴 뒤 남은 껍데기(옛 행)를 지운다.
DELETE FROM famous_menu_by_region f
WHERE split_part(f.region3rd, '_', 1) IN ('중구','동구','서구','남구','북구','강서구','고성군')
  AND split_part(f.region3rd, '_', 3) = ''
  AND NOT EXISTS (SELECT 1 FROM hot_store_ranking h WHERE h.famous_menu_by_region_id = f.id);
