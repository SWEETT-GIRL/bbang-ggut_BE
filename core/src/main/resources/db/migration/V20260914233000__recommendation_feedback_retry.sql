-- "다른 가게로 다시 받기"(#661 다시 추천 계약, 09-14 확정) — 서버가 이전 추천에 남기는 RETRY 반응.
--
-- FE 는 다시 받기 때 따로 피드백을 보내지 않는다. 서버가 previousRecommendationId 를 처리하면서 이전 추천에 action = RETRY
-- (추천 단위, store_id NULL)를 기록한다. DISMISS("담지 않고 닫았다")와 섞으면 지표가 흐려져 값을 따로 둔다.
-- 피드백 API 로 RETRY 가 오면 400 — 서버 전용 값이다.
ALTER TABLE recommendation_feedback DROP CONSTRAINT ck_recommendation_feedback_action;
ALTER TABLE recommendation_feedback ADD CONSTRAINT ck_recommendation_feedback_action
    CHECK (action IN ('VIEW', 'STORE_TAP', 'STORE_UNCHECK', 'SAVE', 'DISMISS', 'LIKE', 'DISLIKE', 'RETRY'));
COMMENT ON COLUMN recommendation_feedback.action IS
    'VIEW | STORE_TAP | STORE_UNCHECK | SAVE | DISMISS | LIKE | DISLIKE | RETRY. LIKE · DISLIKE 는 결과 시트의 👍/👎 (코스 단위, store_id NULL). '
    'RETRY 는 "다른 가게로 다시 받기" — 서버가 다시 추천을 처리하며 이전 추천에 남긴다(코스 단위, store_id NULL).';
