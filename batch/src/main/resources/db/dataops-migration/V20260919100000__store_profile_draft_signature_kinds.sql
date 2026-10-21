-- 대표 메뉴를 빵 종류로 푼 목록 (dataops #11, 2026-09-19)
--
-- 회원의 "자주 먹는 빵 종류"(BE user_taste_profile.top_kinds)는 빵 기록에서만 나오는데, 빵 기록을 쓰는 회원이 양의 신호가 있는
-- 회원의 28%뿐이다(랩 124/445명 — 기록의 93%는 종류로 풀리므로 데이터 누락이 아니라 사용 빈도 문제). 빵 기록이 없는 회원은
-- 좋아한 가게들의 대표 메뉴로 "자주 찾는 빵 종류"를 추정한다(BE 가 계산, 이유 문장에 먼저 쓰고 점수 반영은 측정 뒤).
-- 가게 kinds 전체는 가게당 20종 안팎이라 너무 넓어, 그 가게에서 실제로 사는 것에 가까운 대표 메뉴를 쓴다.
-- 종류 풀이 규칙(kind_of_label · 표기 변형)이 dataops 에 있어서 두 곳에 같은 규칙이 생기지 않게 여기서 풀어 둔다.
-- 운영 store_profile 에도 같은 열이 생긴다(BE 마이그레이션). 적재 스크립트는 대상에 열이 있을 때만 보낸다.
ALTER TABLE store_profile_draft ADD COLUMN IF NOT EXISTS signature_kinds TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN store_profile_draft.signature_kinds IS
    '대표 메뉴(signature_menus)를 빵 종류로 푼 것 — 대표 메뉴 순서, 중복 없음. 일반 종류(빵·기타·요거트)는 넣지 않는다.';
