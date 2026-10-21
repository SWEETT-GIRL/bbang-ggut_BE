-- 누가 내 빵투어를 찜했는지 알려주는 알림.
--
-- saved_courses에서 파생하지 않고 테이블을 따로 두는 이유: 찜을 해제하면 saved_courses의 행이
-- 지워지는데, 그때 알림까지 같이 사라지면 "분명히 봤는데 없어졌다"가 된다. 읽음 시각을 얹을
-- 자리도 필요하다.
--
-- (actor_id, course_id) 유니크: 찜을 껐다 켰다 반복해도 알림은 한 사람당 한 줄이다.
-- 다시 찜하면 새 줄을 만드는 대신 notified_at을 갱신하고 read_at을 비워 맨 위로 올린다.
-- 정렬을 created_at이 아니라 notified_at으로 하는 이유가 이것이다 — created_at은 감사용이라
-- @CreatedDate가 처음 값을 박고 다시 안 바꾼다.
CREATE TABLE course_save_notification (
	id BIGSERIAL PRIMARY KEY,
	owner_id BIGINT NOT NULL REFERENCES member (id),
	actor_id BIGINT NOT NULL REFERENCES member (id),
	course_id BIGINT NOT NULL REFERENCES courses (id),
	notified_at TIMESTAMP NOT NULL,
	read_at TIMESTAMP,
	created_at TIMESTAMP NOT NULL,
	updated_at TIMESTAMP,
	CONSTRAINT uk_course_save_notification_actor_course UNIQUE (actor_id, course_id)
);

-- 목록 조회는 항상 "내 알림을 최신순으로" 한 가지 모양이다.
CREATE INDEX idx_course_save_notification_owner_notified
	ON course_save_notification (owner_id, notified_at DESC);

-- 벨 배지(미읽음 개수)는 읽은 알림을 아예 안 본다.
CREATE INDEX idx_course_save_notification_unread
	ON course_save_notification (owner_id)
	WHERE read_at IS NULL;
