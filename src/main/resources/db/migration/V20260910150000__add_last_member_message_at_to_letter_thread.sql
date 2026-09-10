-- 사용자가 이 대화에 마지막으로 편지를 쓴 시각. NULL 이면 사용자는 아직 한 번도 말을 걸지 않았다.
--
-- 왜 필요한가 — 어드민 편지함이 회원 목록이 돼 버렸다. 가입할 때 인사 한 통이 들어가면서
-- 대화방이 생기고(WelcomeLetterService), 그 방의 last_message_at 이 가입 시각이라 신규 가입
-- 한 명당 한 줄씩 실제 문의 위로 올라온다. 2026-09-10 기준 대화 2,719개 중 사용자가 말을 건
-- 것은 네 개였고, 나머지는 가입 인사와 2026-09-07 일괄 발신 공지였다. 어드민 목록은 첫
-- 페이지만 부르므로 밀려난 문의는 스크롤로도 닿지 않는다.
--
-- 왜 letter_message 를 EXISTS 로 보지 않는가 — 목록은 last_message_at 내림차순인데, 걸러질
-- 방이 절대 다수라 한 페이지를 채우려면 최근 가입자 수백 명을 훑고 지나가야 한다. 회원이
-- 늘수록 그 훑는 거리가 길어진다. 값을 스레드가 들고 있으면 부분 인덱스에서 스무 줄만 읽는다.
--
-- 왜 boolean 이 아닌 시각인가 — resolved_at 과 같은 이유다. "사용자가 마지막으로 말을 건 때"는
-- 응대가 얼마나 늦었는지를 볼 때 쓰는 값이고, 컬럼 하나 값이면 충분하다.
ALTER TABLE letter_thread ADD COLUMN last_member_message_at TIMESTAMP;

-- 이미 오간 대화를 목록에 되돌린다. 사용자가 쓴 편지가 한 통도 없는 방은 NULL 로 남아 빠진다.
UPDATE letter_thread t
SET last_member_message_at = (
	SELECT MAX(m.created_at)
	FROM letter_message m
	WHERE m.thread_id = t.id AND m.sender = 'MEMBER'
);

-- 어드민 목록 전용 인덱스. 조건을 인덱스가 들고 있어 걸러질 방은 아예 들어오지 않는다.
CREATE INDEX idx_letter_thread_inquiry ON letter_thread (last_message_at DESC)
	WHERE last_member_message_at IS NOT NULL;
