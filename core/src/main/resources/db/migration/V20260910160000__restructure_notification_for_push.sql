-- 알림을 원본·우편함·발송대장 셋으로 가른다.
--
-- 지금 구조는 notification 한 행이 곧 한 사람의 우편함 줄이다. 전체공지를 보내면 회원 수만큼
-- 행이 생기고 title·body가 통째로 복사된다. 문구를 고치려면 수만 행을 UPDATE 해야 하고,
-- "누구에게 보냈나"와 "실제로 나갔나"를 구분할 자리가 없다.
--
-- 푸시를 붙이면서 그 구분이 필요해진다. 인앱에는 쌓였는데 푸시는 안 나가는 경우가 정상적으로
-- 존재하기 때문이다 -- 수신 거부, 토큰 없음, 발송 실패, 아직 발송 전. 우편함과 발송대장이
-- 갈라져 있어야 이 상태들을 있는 그대로 적을 수 있다.
--
--   notification            무엇을 알리는가          공지 1건 = 1행
--   notification_recipient  누구의 알림함에 있는가    사람마다 1행, 읽음도 여기
--   notification_delivery   어느 채널로 실제 나갔나   푸시 발송 결과, 기기 단위

-- ── 1) 알림 원본 ────────────────────────────────────────────────────────────

-- 새 notification 이 같은 이름을 써야 하므로 옛 테이블을 비켜둔다. 이 마이그레이션 끝에서 지운다.
--
-- 한 릴리즈 살려두는 안도 있었으나 접었다. rename 하는 순간 옛 코드는 어차피 이 테이블을
-- 못 찾고(ddl-auto=validate), 엔티티를 지우면 코드에서 닿을 수도 없다 -- 남겨봐야 DB 안에
-- 박제된 데이터일 뿐 되돌리는 수단이 아니다. 되돌릴 자리는 배포 전 스냅샷이 맡는다.
ALTER TABLE notification RENAME TO notification_legacy;
ALTER INDEX idx_notification_recipient_notified RENAME TO idx_notification_legacy_recipient_notified;
ALTER INDEX idx_notification_unread RENAME TO idx_notification_legacy_unread;
ALTER INDEX uk_notification_actor_target RENAME TO uk_notification_legacy_actor_target;

CREATE TABLE notification (
	id BIGSERIAL PRIMARY KEY,
	type VARCHAR(30) NOT NULL,

	-- 서버가 문구를 갖는 알림(NOTICE·AD·제보 결과)만 채운다. 여기 있으면 앱 배포 없이 고칠 수 있다.
	title VARCHAR(200),
	body TEXT,
	-- 탭했을 때 갈 앱 내부 경로(/course/12). 활동 알림은 target 으로 앱이 조립하므로 비어 있다.
	deep_link VARCHAR(500),

	-- 행동한 사람. 관계로 둔다 -- 닉네임은 바뀌면 최신으로 보여야 하고, 탈퇴 여부도 조회 시점에 봐야 한다.
	actor_id BIGINT REFERENCES member (id),

	-- 대상을 외래키로 묶지 않고 펼쳐 두는 이유: 알림마다 대상 도메인이 달라 전부 외래키로 걸면
	-- 컬럼이 도메인 수만큼 늘어난다. 제목을 그때 값으로 박아 두면 조회에 조인이 필요 없고,
	-- 나중에 코스 제목이 바뀌어도 "그때 그 이름"이 남아 문구가 과거와 어긋나지 않는다.
	target_type VARCHAR(20),
	target_id BIGINT,
	target_title VARCHAR(200),

	-- 타입마다만 필요한 부수 값. 제보 결과의 reportId·moderationActionId 처럼 목록에 실려 나가고
	-- 푸시 data 에도 그대로 들어가는 것들이다.
	--
	-- 컬럼으로 펼치지 않는 이유: 타입이 늘 때마다 그 타입만 쓰는 컬럼이 붙어 대부분의 행에서
	-- NULL 인 자리가 계속 쌓인다. 조건으로 걸어 찾을 일이 없는 값이라 한 덩어리로 둔다 --
	-- 여기에 담긴 것으로 WHERE 를 쓰고 싶어지면 그때 그 값은 컬럼이 될 자격이 있는 것이다.
	payload TEXT,

	created_at TIMESTAMP NOT NULL,
	updated_at TIMESTAMP,

	-- 이관용. 아래에서 우편함을 채운 뒤 둘 다 지운다.
	legacy_id BIGINT,
	legacy_report_id BIGINT
);

-- 같은 사람이 같은 대상에 같은 행동을 반복해도 알림은 한 줄이다(찜 껐다 켜기).
-- 활동 알림은 수신자가 한 명이라 원본 쪽에 걸어도 서로 다른 사람의 알림이 충돌하지 않는다.
-- 공지·제보 결과는 actor 가 없어 이 제약을 타지 않는다.
CREATE UNIQUE INDEX uk_notification_actor_target
	ON notification (actor_id, type, target_type, target_id)
	WHERE actor_id IS NOT NULL;

-- ── 2) 각자의 우편함 ────────────────────────────────────────────────────────

CREATE TABLE notification_recipient (
	id BIGSERIAL PRIMARY KEY,
	notification_id BIGINT NOT NULL REFERENCES notification (id),
	member_id BIGINT NOT NULL REFERENCES member (id),

	-- 정렬 기준. 다시 찜하면 새 줄을 만드는 대신 이 값을 갱신해 맨 위로 올린다.
	-- created_at 으로 정렬하지 않는 이유가 이것이다 -- created_at 은 감사용이라 처음 값을 박고 안 바뀐다.
	notified_at TIMESTAMP NOT NULL,
	read_at TIMESTAMP,

	created_at TIMESTAMP NOT NULL,
	updated_at TIMESTAMP,

	-- 같은 알림이 같은 사람의 알림함에 두 번 꽂히지 않는다. 공지 재발송 사고의 방어선이다.
	CONSTRAINT uq_notification_recipient UNIQUE (notification_id, member_id)
);

-- 목록은 항상 "내 알림을 최신순으로" 한 가지 모양이다.
CREATE INDEX idx_notification_recipient_member_notified
	ON notification_recipient (member_id, notified_at DESC);

-- 벨 배지(미읽음 개수)는 읽은 알림을 아예 안 본다.
CREATE INDEX idx_notification_recipient_unread
	ON notification_recipient (member_id) WHERE read_at IS NULL;

-- ── 3) 발송 대장 ────────────────────────────────────────────────────────────

-- 채널별 발송 결과를 한곳에서 본다. 인앱도 채널이라 여기 쌓는다 -- notification_recipient 와
-- 겹쳐 보이지만 역할이 다르다. 우편함은 "지금 알림함에 무엇이 있나"이고, 대장은 "언제 어느
-- 채널로 나갔나"다. 나중에 이메일이 붙어도 모양이 같다.
--
-- 눌렸는지는 적지 않는다. 서버는 알 방법이 없고 -- FCM 은 보낸 것까지만 알려준다 -- 앱이
-- 되돌려줘야 하는데, 앱은 이미 그것을 Amplitude 로 찍고 있다. 같은 사실을 두 군데 쌓지 않는다.
-- 발송 결과를 어드민에서 보게 되는 날 clicked_at 을 더하면 된다.
--
-- 대장은 이번 릴리즈부터 쌓는다. 이관해 온 옛 알림에는 행이 없다 -- 그때 무엇이 나갔는지
-- 기록이 없어서다. 없는 것을 지어내는 것보다 비어 있는 편이 정직하다.
CREATE TABLE notification_delivery (
	id BIGSERIAL PRIMARY KEY,
	notification_id BIGINT NOT NULL REFERENCES notification (id),
	member_id BIGINT NOT NULL REFERENCES member (id),

	-- 기기를 구분해 적지 않는다. 한 사람에 한 줄이고, 토큰이 여러 개면 하나라도 성공했을 때
	-- SENT 다. 죽은 토큰 정리는 FcmService 가 발송 응답을 보고 바로 하므로 여기를 볼 일이 없다.

	channel VARCHAR(20) NOT NULL,
	status VARCHAR(20) NOT NULL,
	-- FCM 이 돌려준 에러코드(UNREGISTERED 등). 사람이 읽을 문장이 아니라 판정에 쓰는 값이다.
	fail_reason VARCHAR(200),

	sent_at TIMESTAMP NOT NULL,

	CONSTRAINT ck_notification_delivery_channel CHECK (channel IN ('PUSH', 'IN_APP')),
	CONSTRAINT ck_notification_delivery_status CHECK (status IN ('SENT', 'FAILED')),

	-- 한 사람의 한 알림에 채널마다 한 줄이다. 재시도는 새 줄을 만드는 대신 이 줄의 status 를
	-- 고친다 -- 시도 이력이 필요해진 적이 없고, 줄이 늘면 "결국 갔나"를 매번 집계해야 한다.
	-- 덤으로 notification_id 가 선두라 "이 공지 발송 결과" 조회도 이 인덱스를 탄다.
	CONSTRAINT uq_notification_delivery UNIQUE (notification_id, member_id, channel)
);

-- 따로 인덱스를 두지 않는다. 클릭 회신은 delivery id 로 한 행을 집으니 PK 조회고,
-- notification_id 로 훑는 조회는 위 유니크 제약이 만든 인덱스가 받는다.

-- ── 4) 기존 알림 이관 ───────────────────────────────────────────────────────

-- 4-1) 공지: 같은 발송이 사람 수만큼 복사돼 있다. (title, body, link, created_at) 이 같으면
--      한 번의 발송이었다고 보고 하나로 접는다. 이게 이번 구조 변경의 이유 그 자체다.
WITH notice AS (
	SELECT title, body, link, created_at, MIN(id) AS legacy_id
	FROM notification_legacy
	WHERE type = 'NOTICE'
	GROUP BY title, body, link, created_at
)
INSERT INTO notification (type, title, body, deep_link, created_at, updated_at, legacy_id)
SELECT 'NOTICE', title, body, link, created_at, created_at, legacy_id
FROM notice;

-- 접힌 공지의 우편함. 원본과 (title, body, link, created_at) 으로 다시 맞춘다.
-- NULL 이 섞이므로 = 이 아니라 IS NOT DISTINCT FROM 으로 비교한다.
INSERT INTO notification_recipient (notification_id, member_id, notified_at, read_at, created_at, updated_at)
SELECT n.id, l.recipient_id, l.notified_at, l.read_at, l.created_at, l.updated_at
FROM notification_legacy l
JOIN notification n
	ON n.type = 'NOTICE'
	AND n.title IS NOT DISTINCT FROM l.title
	AND n.body IS NOT DISTINCT FROM l.body
	AND n.deep_link IS NOT DISTINCT FROM l.link
	AND n.created_at = l.created_at
WHERE l.type = 'NOTICE';

-- 4-2) 활동 알림(찜 등): 수신자가 한 명이라 1:1 로 옮긴다.
INSERT INTO notification (
	type, actor_id, target_type, target_id, target_title, created_at, updated_at, legacy_id
)
SELECT type, actor_id, target_type, target_id, target_title, created_at, updated_at, id
FROM notification_legacy
WHERE type <> 'NOTICE';

INSERT INTO notification_recipient (notification_id, member_id, notified_at, read_at, created_at, updated_at)
SELECT n.id, l.recipient_id, l.notified_at, l.read_at, l.created_at, l.updated_at
FROM notification_legacy l
JOIN notification n ON n.legacy_id = l.id
WHERE l.type <> 'NOTICE';

-- 4-3) 제보 결과 알림을 흡수한다. 지금까지 자기 전용 테이블과 자기 전용 조회 API 를 갖고 있었다.
--      알림함이 둘로 갈려 있을 이유가 없고, 푸시 파이프라인을 두 벌 만들 이유는 더 없다.
--
--      action 으로 타입이 갈린다. 제재(MODERATION_ACTION)는 이의제기 흐름이 붙어 있어 앱이
--      다르게 그려야 하므로 같은 목록에 있되 타입을 따로 준다.
INSERT INTO notification (
	type, title, body, target_type, target_id, target_title, payload,
	created_at, updated_at, legacy_report_id
)
SELECT
	CASE r.action
		WHEN 'MODERATION_ACTION' THEN 'MODERATION_ACTION'
		WHEN 'ABUSE_REPORT_RESULT' THEN 'ABUSE_REPORT_RESULT'
		ELSE 'CONTRIBUTION_RESULT'
	END,
	r.title, r.message,
	CASE WHEN r.store_id IS NOT NULL THEN 'STORE' END,
	r.store_id,
	s.name,
	-- 앱이 이 값들로 이의제기 화면을 열고 목록 한 줄을 그린다. 하나라도 빠지면 옛 알림만
	-- 눌리지 않는 상태가 된다. strip_nulls 로 없는 키는 아예 넣지 않는다.
	jsonb_strip_nulls(jsonb_build_object(
		'reportId', r.report_id,
		'contributionReportId', r.contribution_report_id,
		'abuseReportId', r.abuse_report_id,
		'moderationActionId', r.moderation_action_id,
		'reportKind', r.report_kind,
		'action', r.action
	))::text,
	r.sent_at, r.sent_at,
	r.id
FROM report_notification r
LEFT JOIN store s ON s.id = r.store_id;

INSERT INTO notification_recipient (notification_id, member_id, notified_at, read_at, created_at, updated_at)
SELECT n.id, r.member_id, r.sent_at, r.read_at, r.sent_at, r.sent_at
FROM report_notification r
JOIN notification n ON n.legacy_report_id = r.id;

-- 이관 끝. 임시 컬럼을 지운다.
ALTER TABLE notification DROP COLUMN legacy_id;
ALTER TABLE notification DROP COLUMN legacy_report_id;

-- ── 5) 옛 테이블 정리 ──────────────────────────────────────────────────────

-- 데이터는 위에서 전부 새 구조로 옮겼다. 옛 테이블을 남겨두면 알림이 두 군데 있는 것처럼
-- 보여 다음 사람이 어느 쪽이 진짜인지 물어야 한다. 되돌릴 자리는 배포 전 스냅샷이다.

-- 이미 죽은 테이블. V20260903170000 에서 notification 으로 옮기고 "다음 릴리즈에서 DROP"
-- 이라고 적어둔 것이 이번이다.
DROP TABLE IF EXISTS course_save_notification;

-- 4-1·4-2 에서 새 구조로 옮겼다.
DROP TABLE notification_legacy;

-- 4-3 에서 새 구조로 옮겼다. 조회 API 는 새 알림함으로 갈아끼운다.
DROP TABLE report_notification;
