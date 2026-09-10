-- store.category 를 "가게 분류"로 다시 쓴다.
--
-- 왜 — 이 컬럼은 원래 카카오 카테고리에 "양식"이 들어가면 WESTERN 을 박는 용도로만 있었다.
-- 그 판정은 쓰는 곳이 없어졌고(참조하던 findActiveWesternStoresByKeyword 는 호출부 0), 빵집 여부는
-- store_profile.bakery_score 가 대신한다. 반면 네이버가 가게마다 주는 카테고리(베이커리·케이크전문·
-- 떡,한과 …)는 46,578곳 전부에 있는데 담을 자리가 없어 store_profile.attrs 안에 묻혀 있었다.
-- 가게 자체의 성질이므로 store 에 둔다.
--
-- 값은 StoreCategory enum 의 이름(BAKERY, CAFE_DESSERT …)이다. 네이버·카카오 원문 문자열을 그대로
-- 넣지 않는 이유가 여기 있다 — 외부 표기가 바뀌면 enum 의 매핑 목록만 고치면 되고 저장된 값은 그대로다.
-- 대신 enum 이름을 바꾸면 데이터를 옮겨야 하므로 이름은 바꾸지 않는다.
--
-- 기존 WESTERN 701건(ACTIVE 693 · DELETED 8)은 NULL 로 되돌린다. 판정 근거였던 카카오 원문이
-- 남아 있지 않아 새 분류로 옮길 수 없고, 실제로는 "음식점 > 양식 > 베이커리" 같은 빵집이 대부분이라
-- RESTAURANT 로 옮기면 오히려 틀린 값이 된다. dataops 가 네이버 카테고리로 전건 채운다.

UPDATE store SET category = NULL WHERE category = 'WESTERN';

COMMENT ON COLUMN store.category IS
	'가게 분류. StoreCategory enum 이름(BAKERY, CAFE_DESSERT, CAKE, RICE_CAKE, ... , OTHER)을 저장한다. '
	'네이버·카카오 원문 카테고리 → 우리 분류 매핑은 enum 안에 있으니 외부 표기가 바뀌어도 이 컬럼은 안 건드린다. '
	'NULL 은 "외부 카테고리를 아직 못 받음", OTHER 는 "값은 받았는데 우리 분류에 없음"으로 서로 다르다.';
