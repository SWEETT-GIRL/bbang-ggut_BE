-- 찹쌀떡 두 행의 이름이 가리키는 곳과 엇갈려 있어 맞바꾼다.
--
-- 둘은 같은 디저트를 파는 데가 다른 것이다.
--   한정선 직영 매장(CHAIN · brand 한정선)      ← 이쪽이 "한정선 찹쌀떡" 이어야 한다
--   GS25 협업 상품(CONVENIENCE · brand GS25)   ← 이쪽이 "요거트 찹쌀떡" 이어야 한다
-- 그런데 이름이 반대로 붙어 있어, 칩에 "요거트 찹쌀떡" 이라 쓰고 한정선 매장을 보여주고 있었다.
--
-- item_type·brand 는 그대로 두고 이름만 바꾼다 — 각 행이 가리키는 표는 맞았고 이름만 틀렸다.
-- name 이 UNIQUE 라 한 번에 맞바꾸면 부딪힌다. 임시 이름을 거쳐 세 걸음으로 간다.

UPDATE dessert_item SET name = '__chapssaltteok_swap' WHERE name = '요거트 찹쌀떡';
UPDATE dessert_item SET name = '요거트 찹쌀떡'          WHERE name = '한정선 요거트 찹쌀떡';
UPDATE dessert_item SET name = '한정선 찹쌀떡'          WHERE name = '__chapssaltteok_swap';

-- 별칭도 행을 따라간다. 수집 스크립트가 이 값으로 블로그·인스타를 검색하므로,
-- 브랜드 매장 쪽에는 브랜드 이름이, 편의점 쪽에는 GS25 표기가 남아야 한다.
UPDATE dessert_item
SET aliases = ARRAY['한정선 찹쌀떡', '한정선 요거트 찹쌀떡', '요거트찹쌀떡', '과일 찹쌀떡'],
    updated_at = NOW()
WHERE name = '한정선 찹쌀떡';

UPDATE dessert_item
SET aliases = ARRAY['GS25 한정선', '한정선 요거트 찹쌀떡 GS25', '요거트 찹쌀떡'],
    updated_at = NOW()
WHERE name = '요거트 찹쌀떡';
