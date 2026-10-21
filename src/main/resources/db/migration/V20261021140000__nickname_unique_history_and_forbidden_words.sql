-- 닉네임 자유 입력 (2026-10-21, 팔로우 · 커뮤니티 P1). 사람 이름은 닉네임 하나이므로 살아 있는 회원끼리 유일해야 한다.
-- 유일 비교는 정규화값(NFC · 소문자 · 공백 제거)으로 한다 — "여린 바타르" 와 "여린바타르" 는 같은 이름이다.
-- 기존 닉네임(띄어쓰기 · 11~13자)은 건드리지 않는다. 새 규칙은 바꿀 때만 적용한다.

-- 1) 정규화 컬럼. 앱 코드(NicknameNormalizer)와 같은 규칙: NFC → lower → 공백 제거.
ALTER TABLE member ADD COLUMN nickname_normalized VARCHAR(100);
ALTER TABLE member ADD COLUMN nickname_changed_at TIMESTAMP;

UPDATE member SET nickname_normalized = lower(regexp_replace(normalize(nickname, NFC), '\s', '', 'g'));

-- 2) 살아 있는 회원끼리 겹치는 닉네임: 같은 정규화값 중 나중 가입자(id 큰 쪽)에게 2자리 번호를 붙인다(01부터).
--    탈퇴자는 전부 '탈퇴한 회원'이라 빼고, 사람끼리(USER)만 본다. nickname_changed_at 은 NULL 로 둬 14일 간격 없이 바로 바꿀 수 있다.
CREATE TEMP TABLE renamed_member ON COMMIT DROP AS
SELECT id,
       nickname || lpad((row_number() OVER (PARTITION BY nickname_normalized ORDER BY id) - 1)::text, 2, '0') AS new_nickname,
       row_number() OVER (PARTITION BY nickname_normalized ORDER BY id) AS seq
FROM member
WHERE deleted_at IS NULL AND member_type = 'USER';
DELETE FROM renamed_member WHERE seq = 1;

UPDATE member m
SET nickname = r.new_nickname,
    nickname_normalized = lower(regexp_replace(normalize(r.new_nickname, NFC), '\s', '', 'g'))
FROM renamed_member r
WHERE m.id = r.id;

-- 3) 번호가 붙은 회원에게 알림 하나씩. 앱은 이 알림을 누르면 닉네임 바꾸기를 연다(deep_link 는 FE 와 맞춘 값).
INSERT INTO notification (type, channel, title, body, deep_link, created_at)
SELECT 'NOTICE', 'IN_APP',
       '닉네임이 겹쳐서 ' || r.new_nickname || '(으)로 바뀌었어요',
       '같은 닉네임을 쓰는 분이 있어 번호를 붙였어요. 지금 바로 원하는 닉네임으로 바꿀 수 있어요.',
       '/my/nickname', now()
FROM renamed_member r;

INSERT INTO notification_recipient (notification_id, member_id, notified_at, created_at)
SELECT n.id, r.id, now(), now()
FROM renamed_member r
JOIN notification n ON n.type = 'NOTICE' AND n.deep_link = '/my/nickname'
    AND n.title = '닉네임이 겹쳐서 ' || r.new_nickname || '(으)로 바뀌었어요';

-- 4) 이제 겹치는 게 없으니 유일 인덱스. 탈퇴자('탈퇴한 회원')는 빼야 한다.
ALTER TABLE member ALTER COLUMN nickname_normalized SET NOT NULL;
CREATE UNIQUE INDEX uq_member_nickname_normalized_alive
    ON member (nickname_normalized) WHERE deleted_at IS NULL;
-- 빵순이 찾기(앞글자 일치)용
CREATE INDEX idx_member_nickname_normalized_prefix
    ON member (nickname_normalized text_pattern_ops) WHERE deleted_at IS NULL AND member_type = 'USER';

-- 5) 이력 — 사칭 신고 검토용
CREATE TABLE nickname_history (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES member (id),
    nickname VARCHAR(100) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);
CREATE INDEX idx_nickname_history_member ON nickname_history (member_id, changed_at DESC);

-- 6) 금칙어. kind = BLOCK(막는 말) / ALLOW(막는 말을 품었지만 괜찮은 말, 예: 시발점). 어드민이 고친다.
CREATE TABLE forbidden_word (
    id BIGSERIAL PRIMARY KEY,
    word VARCHAR(50) NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('BLOCK', 'ALLOW')),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_forbidden_word UNIQUE (word, kind)
);
