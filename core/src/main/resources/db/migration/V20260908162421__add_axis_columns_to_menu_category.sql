-- 빵BTI 4축을 메뉴 종류에 붙인다 (취향 추천 설계 v2.2 §4, 이슈 #659).
--
-- 축 값의 약속: A극 -1 / B극 +1.
--   ① 맛     axis_taste    달콤(-1) / 담백(+1)
--   ② 식감   axis_texture  부드(-1) / 바삭(+1)
--   ③ 속     axis_filling  풍부(-1) / 심플(+1)
--   ④ 분위기 axis_vibe     클래식(-1) / 트렌디(+1)
-- 메뉴 종류는 극값(정수)이고, 가게·사용자 축은 이 값의 가중 평균이라 -1~+1 사이 소수가 된다.
-- 가게(store_profile)·사용자(user_taste_profile)도 같은 이름의 컬럼 4개를 쓴다.
--
-- 값은 여기서 넣지 않는다. 매핑 초안의 확신도 "낮음" 17행이 검수 대기라, 검수 확정본을 별도 마이그레이션으로
-- UPDATE 한다. 그때까지 네 컬럼은 NULL 이고, NULL 은 "근거 없음"이다 (0 = 중간이 아니다).
--
-- 빵이 아닌 종류(떡·빙수·아이스크림·요거트)를 위한 별도 스위치는 두지 않는다. 네 축을 NULL 로 두면
-- 그 자체가 "근거 없음"이라 가중 평균에서 빠진다 — 같은 일을 하는 장치를 둘 두지 않는다.
-- (menu.is_bakery_item 은 음료·식사·굿즈를 거르는 다른 층의 값이라 이 자리를 대신하지 않는다.)
ALTER TABLE menu_category
    ADD COLUMN axis_taste   SMALLINT,
    ADD COLUMN axis_texture SMALLINT,
    ADD COLUMN axis_filling SMALLINT,
    ADD COLUMN axis_vibe    SMALLINT;

ALTER TABLE menu_category ADD CONSTRAINT ck_menu_category_axis CHECK (
    (axis_taste   IS NULL OR axis_taste   IN (-1, 1)) AND
    (axis_texture IS NULL OR axis_texture IN (-1, 1)) AND
    (axis_filling IS NULL OR axis_filling IN (-1, 1)) AND
    (axis_vibe    IS NULL OR axis_vibe    IN (-1, 1))
);
