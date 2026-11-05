-- 팔로우 · 커뮤니티 P6: 톡 · 같이 간 사람 · 빵투어 일행 초대(앱 안)

-- 톡: 사람마다 하루 1번. 날짜는 서버가 KST 로 계산해 넣는다 — 같은 날 두 번은 PK 가 막는다.
CREATE TABLE member_pat (
	from_member_id BIGINT NOT NULL REFERENCES member (id),
	to_member_id BIGINT NOT NULL REFERENCES member (id),
	kst_date DATE NOT NULL,
	created_at TIMESTAMP NOT NULL,
	PRIMARY KEY (from_member_id, to_member_id, kst_date)
);
CREATE INDEX idx_member_pat_to_date ON member_pat (to_member_id, kst_date DESC);

-- 같이 간 사람. 태그된 사람이 스스로 빼면 removed_at 을 채우고 행은 남긴다 — 같은 기록에 다시 태그하지 못하게.
CREATE TABLE record_companion (
	record_id BIGINT NOT NULL REFERENCES bread_record (id),
	member_id BIGINT NOT NULL REFERENCES member (id),
	created_at TIMESTAMP NOT NULL,
	removed_at TIMESTAMP,
	PRIMARY KEY (record_id, member_id)
);
CREATE INDEX idx_record_companion_member ON record_companion (member_id);

-- 빵투어 일행 초대(앱 안, 팔로잉 · 팔로워에서 고름). 링크 초대(course_invites)는 그대로 둔다.
CREATE TABLE course_member_invite (
	id BIGSERIAL PRIMARY KEY,
	course_id BIGINT NOT NULL REFERENCES courses (id),
	inviter_id BIGINT NOT NULL REFERENCES member (id),
	invitee_id BIGINT NOT NULL REFERENCES member (id),
	status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
	created_at TIMESTAMP NOT NULL,
	responded_at TIMESTAMP
);
-- 같은 사람을 같은 빵투어에 두 번 부르지 않는다(답하기 전까지)
CREATE UNIQUE INDEX uk_course_member_invite_pending
	ON course_member_invite (course_id, invitee_id)
	WHERE status = 'PENDING';
CREATE INDEX idx_course_member_invite_invitee ON course_member_invite (invitee_id, status);

-- 톡 알림은 날마다 새로 생긴다(하루 1번은 member_pat 이 막는다) — 같은 사람 · 같은 대상 유니크에서 PAT 를 뺀다
DROP INDEX uk_notification_actor_target;
CREATE UNIQUE INDEX uk_notification_actor_target
	ON notification (actor_id, type, target_type, target_id)
	WHERE actor_id IS NOT NULL AND type <> 'PAT';
