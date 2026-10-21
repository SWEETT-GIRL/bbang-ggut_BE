-- 찹쌀떡 두 행의 이름이 가리키는 곳과 엇갈려 있어 맞바꾼다.
--
-- 둘은 같은 디저트를 파는 데가 다른 것이다.
--   한정선 직영 매장(CHAIN · brand 한정선)      → "한정선 찹쌀떡"
--   GS25 협업 상품(CONVENIENCE · brand GS25)   → "요거트 찹쌀떡"
-- 이름이 반대로 붙어 있어, 칩에 "요거트 찹쌀떡" 이라 쓰고 한정선 매장을 보여주고 있었다.
--
-- item_type·brand 는 그대로 두고 이름만 바꾼다 — 각 행이 가리키는 표는 맞았고 이름만 틀렸다.
-- name 이 UNIQUE 라 한 번에 맞바꾸면 부딪힌다. 임시 이름을 거쳐 간다.
--
-- ⚠️ 어느 행인지를 이름이 아니라 item_type 으로 못박는다.
-- 이름만으로 고르면 **이미 맞바뀐 DB 에 이 마이그레이션이 돌 때** 첫 문장이 GS25 행을 집어
-- 임시로 옮기고, 마지막 문장이 이미 존재하는 '한정선 찹쌀떡' 을 또 만들어 UNIQUE 로 죽는다.
-- 운영 DB 는 배포가 막혀 있는 동안 rename_dessert_items.py 로 먼저 바꿨다(2026-09-22).
-- item_type 을 걸어두면 그 DB 에서는 한 행도 안 걸려 조용히 넘어간다.

UPDATE dessert_item SET name = '__chapssaltteok_swap_0'
WHERE name = '요거트 찹쌀떡' AND item_type = 'CHAIN';

UPDATE dessert_item SET name = '__chapssaltteok_swap_1'
WHERE name = '한정선 요거트 찹쌀떡' AND item_type = 'CONVENIENCE';

UPDATE dessert_item
SET name = '한정선 찹쌀떡',
    aliases = ARRAY['한정선 찹쌀떡', '한정선 요거트 찹쌀떡', '요거트찹쌀떡', '과일 찹쌀떡'],
    updated_at = NOW()
WHERE name = '__chapssaltteok_swap_0';

UPDATE dessert_item
SET name = '요거트 찹쌀떡',
    aliases = ARRAY['GS25 한정선', '한정선 요거트 찹쌀떡 GS25', '요거트 찹쌀떡'],
    updated_at = NOW()
WHERE name = '__chapssaltteok_swap_1';
