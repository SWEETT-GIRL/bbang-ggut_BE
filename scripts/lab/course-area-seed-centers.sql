-- course_area 시드(V20260914174500)의 중심 좌표·핫플 참조를 산출한 SQL — 재현용 (PR #735 리뷰 제안 ③).
--
-- 랩 PG(운영 덤프 08-20, docker postgres-lab)에서 돌렸다. 결과는 마이그레이션에 값으로 고정했고, 부팅마다 다시 계산하지 않는다.
-- 규칙:
--   중심   = 칸(시·도 + 시군구, 서울은 + 동)의 ACTIVE 가게 가운데 "반경 1,200m 안에 같은 칸 가게가 가장 많은 가게"의 좌표.
--            동률이면 store.id 가 작은 쪽. 같은 칸 가게만 세는 이유는 옆 칸과 겹치지 않게 하려는 것(대구 수성구 ↔ 중구).
--   핫플   = 중심에서 1,200m 안의 HOT_PLACE 가운데 가장 가까운 것. 이름으로 참조한다(id 는 환경마다 다를 수 있다).
--   반경   = 1,200 고정, max_hop = 1,500 고정(정책값). p80 열은 지역 퍼짐 참고용이고 시드에 쓰지 않는다.
--   시군구 칸은 시드에서 region_2nd/3rd 를 NULL 로 둔다 — 여기의 r2/r3 는 "칸에 속한 가게" 를 고르는 데만 쓴다.
-- 후보 집합이 바뀌면(bakery_score 필터 등) 이 SQL 로 다시 계산해 UPDATE 마이그레이션 하나로 고친다.
--
-- 실행: docker exec -i postgres-lab psql -U sweetgirl -d soboro --csv < scripts/lab/course-area-seed-centers.sql
WITH cell(sido, name, r2, r3, ord) AS (VALUES
 ('서울','성수동','성동구','성수동',1),('서울','연남동','마포구','연남동',2),('서울','망원동','마포구','망원동',3),
 ('서울','한남동','용산구','한남동',4),('서울','공덕동','마포구','공덕동',5),('서울','서교동','마포구','서교동',6),
 ('경기','수원시','수원시',NULL,1),('경기','성남시','성남시',NULL,2),('경기','고양시','고양시',NULL,3),('경기','파주시','파주시',NULL,4),('경기','용인시','용인시',NULL,5),
 ('인천','중구','중구',NULL,1),('인천','연수구','연수구',NULL,2),
 ('강원','강릉시','강릉시',NULL,1),('강원','춘천시','춘천시',NULL,2),('강원','속초시','속초시',NULL,3),
 ('충북','청주시','청주시',NULL,1),('충북','충주시','충주시',NULL,2),
 ('충남','천안시','천안시',NULL,1),('충남','공주시','공주시',NULL,2),('충남','아산시','아산시',NULL,3),
 ('세종','조치원읍','조치원읍',NULL,1),('세종','도담동','도담동',NULL,2),
 ('대전','중구','중구',NULL,1),('대전','서구','서구',NULL,2),
 ('경북','경주시','경주시',NULL,1),('경북','안동시','안동시',NULL,2),('경북','포항시','포항시',NULL,3),
 ('대구','중구','중구',NULL,1),('대구','수성구','수성구',NULL,2),
 ('전북','전주시','전주시',NULL,1),('전북','군산시','군산시',NULL,2),
 ('전남','목포시','목포시',NULL,1),('전남','순천시','순천시',NULL,2),('전남','여수시','여수시',NULL,3),('전남','담양군','담양군',NULL,4),
 ('광주','동구','동구',NULL,1),('광주','서구','서구',NULL,2),
 ('경남','창원시','창원시',NULL,1),('경남','진주시','진주시',NULL,2),('경남','통영시','통영시',NULL,3),
 ('울산','중구','중구',NULL,1),('울산','남구','남구',NULL,2),
 ('부산','중구','중구',NULL,1),('부산','해운대구','해운대구',NULL,2),('부산','수영구','수영구',NULL,3),
 ('제주','제주시','제주시',NULL,1),('제주','서귀포시','서귀포시',NULL,2)
),
member AS (
  SELECT c.sido, c.name, s.id, s.location, s.region_3rd
  FROM cell c JOIN store s ON s.status='ACTIVE' AND s.region_1st=c.sido AND s.region_2nd=c.r2 AND (c.r3 IS NULL OR s.region_3rd=c.r3)
),
dense AS (
  SELECT DISTINCT ON (m.sido, m.name) m.sido, m.name, m.id AS medoid_store_id, m.location AS g, m.region_3rd AS dong,
    (SELECT COUNT(*) FROM member t WHERE t.sido=m.sido AND t.name=m.name AND ST_DWithin(t.location, m.location, 1200)) AS own_cnt
  FROM member m ORDER BY m.sido, m.name, own_cnt DESC, m.id
),
spread AS (
  SELECT d.sido, d.name, COUNT(*) AS stores,
    percentile_cont(0.8) WITHIN GROUP (ORDER BY ST_Distance(m.location, d.g)) AS p80
  FROM dense d JOIN member m ON m.sido=d.sido AND m.name=d.name GROUP BY 1,2
),
hp AS (
  SELECT id, name, ST_SetSRID(ST_MakePoint(center_lng, center_lat),4326)::geography AS g
  FROM search_region WHERE type='HOT_PLACE' AND is_active
),
hpref AS (
  SELECT DISTINCT ON (d.sido, d.name) d.sido, d.name, hp.id AS hp_id, hp.name AS hp_name, ROUND(ST_Distance(hp.g, d.g)::numeric) AS hp_dist_m
  FROM dense d JOIN hp ON ST_DWithin(hp.g, d.g, 1200)
  ORDER BY d.sido, d.name, ST_Distance(hp.g, d.g)
)
SELECT c.sido, c.name, c.ord AS display_order, c.r2 AS region_2nd, c.r3 AS region_3rd,
  ROUND(ST_Y(d.g::geometry)::numeric, 5) AS center_lat, ROUND(ST_X(d.g::geometry)::numeric, 5) AS center_lng,
  LEAST(8000, GREATEST(1200, (ROUND(sp.p80 / 500) * 500)::int)) AS max_hop_m,
  ROUND(sp.p80::numeric) AS p80_m,
  h.hp_id, h.hp_name, h.hp_dist_m,
  sp.stores, d.dong AS dense_dong, d.own_cnt, d.medoid_store_id
FROM cell c
JOIN dense d ON d.sido=c.sido AND d.name=c.name
JOIN spread sp ON sp.sido=c.sido AND sp.name=c.name
LEFT JOIN hpref h ON h.sido=c.sido AND h.name=c.name
ORDER BY array_position(ARRAY['서울','경기','인천','강원','충북','충남','세종','대전','경북','대구','전북','전남','광주','경남','울산','부산','제주'], c.sido), c.ord;
