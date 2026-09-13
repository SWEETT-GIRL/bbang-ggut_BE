-- 추천 만족도 — 명시 신호 (09-13 결정, #659 후속 · PR #731). 상권 추천 결과 시트에 코스 단위 👍/👎 를 두고 👎 에는 이유 칩을 붙인다.
--   지금 피드백은 암묵 신호뿐이라(봤다 · 탭 · 체크 해제 · 담았다 · 닫았다) "담지 않았다"가 취향이 아닌 건지 오늘은 아닌 건지 가를 수 없다.
--   명시 신호가 있어야 recommendation.provider · prompt_version · fallback 별로 만족도를 비교할 수 있다 — 그 컬럼들을 둔 이유가 이것이다.
--   표는 운영에서 0 행이라 값 이동은 없다. 09-11 파일(V20260911142251)은 랩에 이미 적용돼 있어 손대지 않고 따로 둔다.
--
-- action 에 LIKE · DISLIKE 를 더한다. 둘 다 추천 전체에 대한 반응이라 store_id 는 NULL 이다.
-- reason 은 DISLIKE 의 이유 칩 코드. 값 체계는 앱의 FeedbackReason enum 이 정한다
--   (NOT_MY_TASTE 취향이 아니에요 · TOO_FAR 너무 멀어요 · ALREADY_VISITED 이미 가봤어요 · BAD_REASON 이유 문장이 이상해요 · OTHER 기타).
--   칩은 제품이 바뀌면 같이 바뀔 목록이라 값 CHECK 는 두지 않고(user_store_interaction.type 과 같은 이유),
--   DISLIKE 가 아닌 반응에 이유가 붙는 것만 막는다.
ALTER TABLE recommendation_feedback DROP CONSTRAINT ck_recommendation_feedback_action;
ALTER TABLE recommendation_feedback ADD CONSTRAINT ck_recommendation_feedback_action
    CHECK (action IN ('VIEW', 'STORE_TAP', 'STORE_UNCHECK', 'SAVE', 'DISMISS', 'LIKE', 'DISLIKE'));

ALTER TABLE recommendation_feedback ADD COLUMN reason VARCHAR(30);
ALTER TABLE recommendation_feedback ADD CONSTRAINT ck_recommendation_feedback_reason
    CHECK (reason IS NULL OR action = 'DISLIKE');

COMMENT ON COLUMN recommendation_feedback.action IS
    'VIEW | STORE_TAP | STORE_UNCHECK | SAVE | DISMISS | LIKE | DISLIKE. LIKE · DISLIKE 는 결과 시트의 👍/👎 (코스 단위, store_id NULL)';
COMMENT ON COLUMN recommendation_feedback.reason IS
    'DISLIKE 의 이유 칩 코드 (앱 FeedbackReason). NOT_MY_TASTE · TOO_FAR · ALREADY_VISITED · BAD_REASON · OTHER. 칩을 고르지 않으면 NULL';
