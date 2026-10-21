-- 로컬 검수표에도 운영 store_profile 이 요구하는 값을 담는다 (2026-09-10)
--
-- 지금까지 draft 는 축·타입·태그·대표메뉴만 갖고 있었는데, 운영 표는 네 가지를 더 요구한다.
-- 적재 직전에 만들면 검수 없이 운영으로 나가므로, 여기서 먼저 만들고 눈으로 본 뒤 옮긴다.
ALTER TABLE store_profile_draft ADD COLUMN IF NOT EXISTS axis_source   VARCHAR(10);
ALTER TABLE store_profile_draft ADD COLUMN IF NOT EXISTS kinds         TEXT[];
ALTER TABLE store_profile_draft ADD COLUMN IF NOT EXISTS attrs         JSONB NOT NULL DEFAULT '{}';
ALTER TABLE store_profile_draft ADD COLUMN IF NOT EXISTS batch_version VARCHAR(20);

COMMENT ON COLUMN store_profile_draft.axis_source IS
    'MENU | REVIEW | BOTH — 축을 어느 재료로 만들었나. 둘 다 없으면 NULL(축 근거 부족).';
COMMENT ON COLUMN store_profile_draft.kinds IS
    '운영 분류 메뉴 종류 ∪ 리뷰 언급 종류. 후보 점수의 종류 겹침 항이 사용자 top_kinds 와 맞댄다.';
COMMENT ON COLUMN store_profile_draft.attrs IS
    '편의·유형 속성 + 인기 prior 재료(리뷰 수·평점). 점수에 넣지 않는다. '
    '별점 0 은 리뷰 없음이 0 으로 들어온 것이라 결측으로 두고 키를 만들지 않는다.';
