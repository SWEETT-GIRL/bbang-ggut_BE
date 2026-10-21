-- 도장 칸 하나 아래 추천 동네 여러 개 (#736, PR #735 리뷰 제안 ①의 후속).
--
-- 왜: 시군구 칸은 중심 1,200m 만 보므로 "서귀포시" 칩은 시내(동홍동)만 추천한다. 중문·성산에 갈 사람에겐 쓸모가 없다.
-- 칩을 잘게 나누면(제주 8칩) 지역마다 규칙이 달라 헷갈리고 카드가 지저분해지며, 반경을 키우면(서귀포 42km = 시군구 전체)
-- 인기 1위가 있는 성산으로 첫 코스가 가고 다시 받을 때마다 동네가 튄다(09-14 검토). 그래서 칩은 도장 칸 48개 그대로 두고,
-- 세부 동네는 이 표의 행으로만 두어 서버가 고른다: ① 코스에 담긴 가게가 반경 안인 동네 ② 내 저장 가게가 있는 동네 ③ 대표 행.
--
-- passport_spot = 빵여권 도장 칸 라벨(예: 서귀포시). 칩은 같은 (sido, passport_spot) 행들의 묶음이고, 칩 id 는 대표 행
-- (display_order 최소), 칩 이름은 passport_spot 이다. name 은 동네 이름(예: 중문)으로 결과 제목에만 보인다.
-- 도장은 기록한 가게 주소로 찍히므로 칩·행 이름이 도장 라벨과 달라도 도장판은 영향이 없다.
ALTER TABLE course_area ADD COLUMN passport_spot VARCHAR(50);
UPDATE course_area SET passport_spot = name;
ALTER TABLE course_area ALTER COLUMN passport_spot SET NOT NULL;
COMMENT ON COLUMN course_area.passport_spot IS
    '빵여권 도장 칸 라벨(FE breadRegions.ts 의 BREAD_SPOTS 와 같은 표기). 칩은 이 값으로 묶여 하나가 되고 칩 이름이 된다. '
    '같은 (sido, passport_spot) 행 가운데 display_order 가 가장 작은 행이 대표(칩 id)이고, 서버가 요청 신호로 동네 행을 고른다.';

-- 관광지 6칸의 대표 행은 이제 "시내" 동네다 — 이름을 동네로 바꾼다. 칩 라벨(passport_spot)은 그대로라 FE 노출은 변하지 않는다.
-- 경주시 행은 처음부터 황리단길 중심(황오동), 해운대구 행은 해운대 중심(중동)이다.
UPDATE course_area SET name = '제주 시내'   WHERE sido = '제주' AND name = '제주시';
UPDATE course_area SET name = '서귀포 시내' WHERE sido = '제주' AND name = '서귀포시';
UPDATE course_area SET name = '황리단길'    WHERE sido = '경북' AND name = '경주시';
UPDATE course_area SET name = '강릉 시내'   WHERE sido = '강원' AND name = '강릉시';
UPDATE course_area SET name = '해운대'      WHERE sido = '부산' AND name = '해운대구';
UPDATE course_area SET name = '신포동'      WHERE sido = '인천' AND name = '중구';

-- 세부 동네 11행. 중심 = 핫플 중심 2,000m 안 + 같은 시·도·도장 시군구 ACTIVE 가게 가운데 최밀집 1,200m 원(같은 칸 가게만)의
-- 가게 좌표(2026-09-14 랩, scripts/lab/course-area-seed-centers.sql 2부). 시군구로 제한하지 않으면 센텀이 광안리(수영구)로,
-- 송정이 기장읍(기장군)으로 끌려간다. 반경 1,200 · max_hop 1,500 · 반경만(region NULL) 은 대표 행과 같다. 11칸 모두 4곳 코스가
-- 나온다(최장 구간 108~666m). 얇은 칸(영종도 7 · 표선 10 · 보문 11 · 송정 11 · 성산 13)은 다시 받기 1~2회면 바닥 — 콘텐츠가
-- 빼려면 is_active = false. display_order 는 그 시·도 대표 행들 뒤 번호라 칩 순서(대표 행)는 그대로다.
INSERT INTO course_area
    (sido, passport_spot, name, search_region_id, center_lat, center_lng, radius_m, max_hop_m, region_2nd, region_3rd, display_order)
SELECT v.sido, v.passport_spot, v.name, hp.id, v.center_lat, v.center_lng, 1200, 1500, NULL, NULL, v.display_order
FROM (VALUES
    ('제주', '제주시',   '애월',        '애월',     33.46461, 126.31572, 3),  -- 애월읍 · 원 안 24곳
    ('제주', '제주시',   '한림·협재',   '협재',     33.39908, 126.24582, 4),  -- 한림읍 · 18곳
    ('제주', '제주시',   '구좌·월정리', '월정리',   33.55472, 126.79887, 5),  -- 구좌읍 · 20곳
    ('제주', '서귀포시', '중문',        '중문',     33.25210, 126.42266, 6),  -- 중문동 · 22곳
    ('제주', '서귀포시', '성산',        '성산',     33.46901, 126.92945, 7),  -- 성산읍 · 13곳
    ('제주', '서귀포시', '표선',        '표선',     33.32491, 126.83244, 8),  -- 표선면 · 10곳
    ('경북', '경주시',   '보문',        '경주보문', 35.83607, 129.28295, 4),  -- 보덕동 · 11곳
    ('강원', '강릉시',   '강문·안목',   '안목',     37.76513, 128.94047, 4),  -- 성덕동 · 20곳
    ('부산', '해운대구', '센텀',        '센텀',     35.16580, 129.14109, 4),  -- 우동 · 42곳
    ('부산', '해운대구', '송정',        '송정',     35.18316, 129.20280, 5),  -- 송정동 · 11곳
    ('인천', '중구',     '영종도',      '영종도',   37.48936, 126.53199, 3)   -- 영종동 · 7곳
) AS v (sido, passport_spot, name, hot_place, center_lat, center_lng, display_order)
LEFT JOIN search_region hp ON hp.type = 'HOT_PLACE' AND hp.is_active AND hp.name = v.hot_place;
