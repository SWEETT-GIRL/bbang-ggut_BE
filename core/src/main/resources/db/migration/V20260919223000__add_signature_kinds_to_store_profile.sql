-- store_profile.signature_kinds — 대표 메뉴를 빵 종류 이름으로 푼 것 (dataops #11 · 2026-09-19).
--
-- signature_menus 는 메뉴 이름 그대로("플레인 포카치아")라 회원 top_kinds 와 맞춰 볼 수 없다. dataops 가 메뉴 분류기로
-- 대표 메뉴를 menu_category 이름으로 풀어 넣는다: 대표 메뉴 순서 · 중복 없음 · 일반 종류(빵 · 기타 · 요거트) 없음.
-- 종류 목록에 없는 대표 메뉴(꽈배기 외 약 11%)는 지어내지 않고 뺀다.
--
-- NOT NULL DEFAULT '{}' — 풀린 종류가 없는 가게는 빈 배열이다. 기존 행과, 이 열을 모르는 적재도 빈 배열로 들어간다.
-- 아직 BE 가 읽는 곳은 없다. 이유 문장 재료로 쓸지는 #662 판정에서, 점수에 넣을지는 측정 뒤에 정한다.
ALTER TABLE store_profile ADD COLUMN signature_kinds TEXT[] NOT NULL DEFAULT '{}';
