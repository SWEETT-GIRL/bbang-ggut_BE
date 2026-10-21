-- 기본 모습 3종(앉기·눈웃음·갸웃)을 값 0 품목으로 둔다 — 처음부터 누구나 가진 모습이라 사지 않고 바로 입힌다.
-- 서버가 "어느 기본 모습을 골랐나"를 알아야 홈과 다른 화면에도 같은 모습이 나온다(지금은 앱 화면에만 남았다).
-- pose 는 앱이 들고 있는 그림 이름(sit · eye-smile · curious)과 같다.
ALTER TABLE shop_items DROP CONSTRAINT chk_shop_items_price;
ALTER TABLE shop_items ADD CONSTRAINT chk_shop_items_price CHECK (price >= 0);

INSERT INTO shop_items (code, kind, pose, name, price, lock_level, sort_order) VALUES
    ('pose-sit',       'POSE', 'sit',       '앉기',   0, NULL, 1),
    ('pose-eye-smile', 'POSE', 'eye-smile', '눈웃음', 0, NULL, 2),
    ('pose-curious',   'POSE', 'curious',   '갸웃',   0, NULL, 3);

UPDATE shop_catalog SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = 1;
