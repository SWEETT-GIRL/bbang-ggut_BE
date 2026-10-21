-- 인천 분구(2026) 반영 — 서구 → 서해구·검단구, 중구·동구 → 영종구·제물포구
--
-- 근거: 카카오 coord2regioncode(법정동 B타입)로 인천 ACTIVE 가게 2,375곳을 전수 역산(dataops 이슈 #5, 2026-09-08)했더니
--       옛 서구·중구·동구는 더 이상 나오지 않고 11개 구·군(강화군·검단구·계양구·남동구·미추홀구·부평구·서해구·연수구·
--       영종구·옹진군·제물포구)만 나온다. 가게가 없는 18개 동은 주소검색·좌표역산으로 교차 확인했다(전부 제물포구).
-- 방식: ① GU 행 4개 신설 ② 옛 구의 DONG 행을 새 부모로 이동(행 id 보존 → DONG 매핑·alias 그대로)
--       ③ 옛 구를 가리키던 GU 매핑(616건)은 가게의 DONG 매핑이 있는 새 구로 이동 ④ 옛 구 행은 is_active=FALSE 로 남긴다(id 보존).
--       재실행 안전(NOT EXISTS / 이동표 기준). 운영 드라이런: 이동표 80행 ↔ DONG 80행 1:1, GU 매핑 616건 전부 새 구 유도.
-- 후속: dataops 가 store.region_2nd/3rd 를 같은 기준으로 갱신(인천 610곳). FE BREAD_SPOTS 인천 항목, famous_menu_by_region 의
--       인천_서구_*/인천_중구_* 키는 별도.

-- 1) 새 GU 4행 (인천 CITY 아래)
INSERT INTO search_region (name, type, level, parent_region_id, display_order, is_active)
SELECT v.name, 'DISTRICT', 'GU', c.id, 0, TRUE
FROM (VALUES ('서해구'), ('검단구'), ('영종구'), ('제물포구')) AS v(name)
JOIN search_region c ON c.name = '인천' AND c.level = 'CITY'
WHERE NOT EXISTS (
    SELECT 1 FROM search_region g WHERE g.parent_region_id = c.id AND g.level = 'GU' AND g.name = v.name);

-- 2) 새 GU 의 기본 alias (기존 GU 관행: alias = normalized_alias = 이름, is_primary)
INSERT INTO search_region_alias (region_id, alias, normalized_alias, is_primary)
SELECT g.id, g.name, g.name, TRUE
FROM search_region g
JOIN search_region c ON c.id = g.parent_region_id AND c.name = '인천' AND c.level = 'CITY'
WHERE g.level = 'GU' AND g.name IN ('서해구', '검단구', '영종구', '제물포구')
  AND NOT EXISTS (SELECT 1 FROM search_region_alias a WHERE a.region_id = g.id AND a.normalized_alias = g.name);

-- 3) DONG 이동표 (옛 구, 동, 새 구) — 80행
CREATE TEMP TABLE _incheon_dong_move (old_gu TEXT, dong TEXT, new_gu TEXT) ON COMMIT DROP;
INSERT INTO _incheon_dong_move (old_gu, dong, new_gu) VALUES
    ('서구', '가정동', '서해구'),
    ('서구', '가좌동', '서해구'),
    ('서구', '검암동', '서해구'),
    ('서구', '경서동', '서해구'),
    ('서구', '공촌동', '서해구'),
    ('서구', '석남동', '서해구'),
    ('서구', '신현동', '서해구'),
    ('서구', '심곡동', '서해구'),
    ('서구', '연희동', '서해구'),
    ('서구', '원창동', '서해구'),
    ('서구', '청라동', '서해구'),
    ('서구', '금곡동', '검단구'),
    ('서구', '당하동', '검단구'),
    ('서구', '대곡동', '검단구'),
    ('서구', '마전동', '검단구'),
    ('서구', '백석동', '검단구'),
    ('서구', '불로동', '검단구'),
    ('서구', '시천동', '검단구'),
    ('서구', '오류동', '검단구'),
    ('서구', '왕길동', '검단구'),
    ('서구', '원당동', '검단구'),
    ('중구', '남북동', '영종구'),
    ('중구', '덕교동', '영종구'),
    ('중구', '무의동', '영종구'),
    ('중구', '운남동', '영종구'),
    ('중구', '운북동', '영종구'),
    ('중구', '운서동', '영종구'),
    ('중구', '을왕동', '영종구'),
    ('중구', '중산동', '영종구'),
    ('중구', '경동', '제물포구'),
    ('중구', '내동', '제물포구'),
    ('중구', '답동', '제물포구'),
    ('중구', '사동', '제물포구'),
    ('중구', '용동', '제물포구'),
    ('중구', '유동', '제물포구'),
    ('중구', '전동', '제물포구'),
    ('중구', '도원동', '제물포구'),
    ('중구', '선린동', '제물포구'),
    ('중구', '선화동', '제물포구'),
    ('중구', '신생동', '제물포구'),
    ('중구', '신포동', '제물포구'),
    ('중구', '율목동', '제물포구'),
    ('중구', '인현동', '제물포구'),
    ('중구', '북성동1가', '제물포구'),
    ('중구', '북성동2가', '제물포구'),
    ('중구', '북성동3가', '제물포구'),
    ('중구', '송월동1가', '제물포구'),
    ('중구', '송월동2가', '제물포구'),
    ('중구', '송월동3가', '제물포구'),
    ('중구', '송학동1가', '제물포구'),
    ('중구', '송학동2가', '제물포구'),
    ('중구', '송학동3가', '제물포구'),
    ('중구', '신흥동1가', '제물포구'),
    ('중구', '신흥동2가', '제물포구'),
    ('중구', '신흥동3가', '제물포구'),
    ('중구', '중앙동1가', '제물포구'),
    ('중구', '중앙동2가', '제물포구'),
    ('중구', '중앙동3가', '제물포구'),
    ('중구', '중앙동4가', '제물포구'),
    ('중구', '해안동1가', '제물포구'),
    ('중구', '해안동2가', '제물포구'),
    ('중구', '해안동3가', '제물포구'),
    ('중구', '해안동4가', '제물포구'),
    ('중구', '관동1가', '제물포구'),
    ('중구', '관동2가', '제물포구'),
    ('중구', '관동3가', '제물포구'),
    ('중구', '항동1가', '제물포구'),
    ('중구', '항동2가', '제물포구'),
    ('중구', '항동3가', '제물포구'),
    ('중구', '항동4가', '제물포구'),
    ('중구', '항동5가', '제물포구'),
    ('중구', '항동6가', '제물포구'),
    ('중구', '항동7가', '제물포구'),
    ('동구', '금곡동', '제물포구'),
    ('동구', '만석동', '제물포구'),
    ('동구', '송림동', '제물포구'),
    ('동구', '송현동', '제물포구'),
    ('동구', '창영동', '제물포구'),
    ('동구', '화수동', '제물포구'),
    ('동구', '화평동', '제물포구');

UPDATE search_region d
SET parent_region_id = ng.id, updated_at = now()
FROM _incheon_dong_move mv
JOIN search_region c  ON c.name = '인천' AND c.level = 'CITY'
JOIN search_region og ON og.parent_region_id = c.id AND og.level = 'GU' AND og.name = mv.old_gu
JOIN search_region ng ON ng.parent_region_id = c.id AND ng.level = 'GU' AND ng.name = mv.new_gu
WHERE d.level = 'DONG' AND d.parent_region_id = og.id AND d.name = mv.dong;

-- 4) 옛 구 GU 매핑 → 새 구 (가게의 DONG 매핑이 가리키는 새 부모). 같은 (가게, 새 구) 매핑이 이미 있으면 옛 것을 지운다
CREATE TEMP TABLE _incheon_gu_remap ON COMMIT DROP AS
SELECT m.id AS map_id, m.store_id,
       (SELECT d.parent_region_id
          FROM store_search_region m2
          JOIN search_region d  ON d.id = m2.region_id AND d.level = 'DONG'
          JOIN search_region ng ON ng.id = d.parent_region_id AND ng.parent_region_id = c.id
                                AND ng.name IN ('서해구', '검단구', '영종구', '제물포구')
         WHERE m2.store_id = m.store_id
         LIMIT 1) AS new_gu_id
FROM store_search_region m
JOIN search_region og ON og.id = m.region_id AND og.level = 'GU' AND og.name IN ('서구', '중구', '동구')
JOIN search_region c  ON c.id = og.parent_region_id AND c.name = '인천' AND c.level = 'CITY';

DELETE FROM store_search_region m
USING _incheon_gu_remap r
WHERE m.id = r.map_id AND r.new_gu_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM store_search_region x WHERE x.store_id = r.store_id AND x.region_id = r.new_gu_id);

UPDATE store_search_region m
SET region_id = r.new_gu_id, updated_at = now()
FROM _incheon_gu_remap r
WHERE m.id = r.map_id AND r.new_gu_id IS NOT NULL;

-- 5) 옛 구 비활성 — 동이 하나도 남지 않은 경우에만 (안전장치). 행·alias 는 남긴다(id 보존, 검색 시 is_active 필터는 BE 확인)
UPDATE search_region g
SET is_active = FALSE, updated_at = now()
FROM search_region c
WHERE c.id = g.parent_region_id AND c.name = '인천' AND c.level = 'CITY'
  AND g.level = 'GU' AND g.name IN ('서구', '중구', '동구')
  AND NOT EXISTS (SELECT 1 FROM search_region d WHERE d.parent_region_id = g.id AND d.level = 'DONG');
