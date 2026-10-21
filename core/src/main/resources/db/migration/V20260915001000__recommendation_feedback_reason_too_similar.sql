-- 👎 이유 칩에 TOO_SIMILAR("비슷한 가게뿐이에요") 추가 (#661, 2026-09-14 결정).
--
-- 취향 점수식(#739)을 켜니 코스 4곳이 같은 빵BTI 타입뿐인 칸이 59칸 중 13~15칸으로 늘었다(프로필 없는 인기순은 2칸).
-- 후처리 "축 거리 기준 비슷한 가게 줄이기"는 취향 적합도와 맞바꾸는 규칙이라 먼저 넣지 않고, 사용자가 실제로 지루해하는지
-- 이 칩으로 본 뒤 정한다. reason 은 값 CHECK 가 없어(제품이 바뀌면 같이 바뀌는 목록) 컬럼 설명만 갱신한다.
COMMENT ON COLUMN recommendation_feedback.reason IS
    'DISLIKE 의 이유 칩 코드 (앱 FeedbackReason). NOT_MY_TASTE · TOO_FAR · ALREADY_VISITED · BAD_REASON · TOO_SIMILAR · OTHER. '
    '칩을 고르지 않으면 NULL. TOO_SIMILAR 는 다양성 후처리를 넣을지 정하는 근거(#661)';
