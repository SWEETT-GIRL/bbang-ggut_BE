-- 알림을 한 테이블로 모은다.
--
-- 지금까지는 course_save_notification 하나뿐이었다 — "누가 내 빵투어를 저장했는지"만 다루는
-- 전용 테이블이라, 공지·초대·좋아요를 넣을 자리가 없었다. type을 들고 있는 테이블 하나로 바꾼다.
--
-- 대상(코스 등)을 외래키로 묶지 않고 (target_type, target_id, target_title)로 펼쳐 두는 이유:
-- 알림마다 대상 도메인이 다른데 전부 외래키로 걸면 컬럼이 도메인 수만큼 늘어난다. 제목을
-- 그때 값으로 박아 두면 조회에 조인이 필요 없고, 나중에 코스 제목이 바뀌어도 "저장할 당시
-- 그 이름"이 남아 알림 문구가 과거와 어긋나지 않는다.
--
-- actor는 관계로 둔다. 닉네임은 바뀌면 최신으로 보여야 하고, 탈퇴 여부도 조회 시점에 봐야 한다.
CREATE TABLE notification (
	id BIGSERIAL PRIMARY KEY,
	recipient_id BIGINT NOT NULL REFERENCES member (id),
	type VARCHAR(30) NOT NULL,

	-- 행동한 사람. 공지처럼 사람이 한 일이 아닌 알림에는 없다.
	actor_id BIGINT REFERENCES member (id),

	-- 탭했을 때 갈 곳. COURSE/RECORD/STORE 같은 도메인과 그 id, 그리고 그때의 이름.
	target_type VARCHAR(20),
	target_id BIGINT,
	target_title VARCHAR(200),

	-- 서버가 문구를 갖는 알림(공지)용. 활동 알림은 앱이 조립하므로 비어 있다.
	title VARCHAR(200),
	body TEXT,
	-- 공지가 특정 화면으로 보낼 때. 앱 내부 경로다(/course/12 같은).
	link VARCHAR(500),

	notified_at TIMESTAMP NOT NULL,
	read_at TIMESTAMP,
	created_at TIMESTAMP NOT NULL,
	updated_at TIMESTAMP
);

-- 기존 찜 알림을 그대로 옮긴다. 코스 제목은 지금 값을 박는다 —
-- 옛 테이블엔 제목이 없어서 이관 시점 값이 최선이다.
INSERT INTO notification (
	recipient_id, type, actor_id, target_type, target_id, target_title,
	notified_at, read_at, created_at, updated_at
)
SELECT n.owner_id, 'COURSE_SAVED', n.actor_id, 'COURSE', n.course_id, c.title,
	n.notified_at, n.read_at, n.created_at, n.updated_at
FROM course_save_notification n
JOIN courses c ON c.id = n.course_id;

-- course_save_notification은 일부러 지우지 않는다. 문제가 생기면 되돌릴 자리가 필요하고,
-- 배포 직후 잠깐은 옛 코드가 돌 수도 있다. 다음 릴리즈에서 DROP한다.

-- 목록은 항상 "내 알림을 최신순으로" 한 가지 모양이다.
CREATE INDEX idx_notification_recipient_notified ON notification (recipient_id, notified_at DESC);

-- 벨 배지(미읽음 개수)는 읽은 알림을 아예 안 본다.
CREATE INDEX idx_notification_unread ON notification (recipient_id) WHERE read_at IS NULL;

-- 같은 사람이 같은 대상에 같은 행동을 반복해도 알림은 한 줄이다(저장 껐다 켜기).
-- 공지는 actor가 없어 이 제약을 타지 않는다 — 어차피 사람마다 한 번씩만 만든다.
CREATE UNIQUE INDEX uk_notification_actor_target
	ON notification (actor_id, type, target_type, target_id)
	WHERE actor_id IS NOT NULL;
