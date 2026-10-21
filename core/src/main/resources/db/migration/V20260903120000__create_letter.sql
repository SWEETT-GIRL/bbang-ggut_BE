-- 개발자와 사용자가 주고받는 편지. 한 회원당 대화방 하나다.
--
-- 알림(course_save_notification)과 테이블을 나누는 이유: 알림은 "일어난 일"을 한 줄씩 쌓는
-- 기록이고 편지는 주고받는 대화다. 한 테이블에 섞으면 sender가 없는 알림 행과 sender가
-- 있는 편지 행이 같이 살게 되고, 목록 정렬과 읽음 규칙도 서로 다르다.
--
-- inquiry 테이블이 이미 있지만 단발 제출(제목+본문)이라 스레드가 없고 답장을 달 자리가 없다.
-- 앱에서 쓰이지도 않는다. 그쪽은 건드리지 않고 편지함이 그 역할을 가져간다.
CREATE TABLE letter_thread (
	id BIGSERIAL PRIMARY KEY,
	member_id BIGINT NOT NULL REFERENCES member (id),
	-- 어드민 목록이 "최근 대화순"이다. 매번 letter_message를 group by 하지 않으려고 여기 둔다.
	last_message_at TIMESTAMP NOT NULL,
	created_at TIMESTAMP NOT NULL,
	updated_at TIMESTAMP,
	CONSTRAINT uk_letter_thread_member UNIQUE (member_id)
);

CREATE INDEX idx_letter_thread_last_message ON letter_thread (last_message_at DESC);

CREATE TABLE letter_message (
	id BIGSERIAL PRIMARY KEY,
	thread_id BIGINT NOT NULL REFERENCES letter_thread (id),
	-- ADMIN | MEMBER. 누가 보냈는지가 말풍선 방향과 읽음 주체를 함께 정한다.
	sender VARCHAR(10) NOT NULL,
	body TEXT NOT NULL,
	-- 받는 쪽이 읽은 시각. 보낸 쪽 기준이 아니라 상대 기준이다 —
	-- sender=ADMIN이면 사용자가 읽은 시각, sender=MEMBER면 어드민이 읽은 시각.
	read_at TIMESTAMP,
	created_at TIMESTAMP NOT NULL,
	updated_at TIMESTAMP
);

-- 대화 조회는 항상 "이 스레드를 시간순으로" 한 가지 모양이다.
CREATE INDEX idx_letter_message_thread_created ON letter_message (thread_id, created_at DESC);

-- 벨 배지(안 읽은 편지 수)는 읽은 편지를 아예 안 본다.
CREATE INDEX idx_letter_message_unread ON letter_message (thread_id, sender) WHERE read_at IS NULL;
