-- 친구 초대(리워드 2.0). 초대받은 사람이 코드를 넣고 30 일 안에 첫 빵을 기록하면 둘 다 부스러기를 받는다.

-- 내 초대 코드. 처음 초대 화면을 열 때 만든다 — 그 전에는 비어 있다.
ALTER TABLE member ADD COLUMN invite_code VARCHAR(8);
CREATE UNIQUE INDEX uq_member_invite_code ON member (invite_code) WHERE invite_code IS NOT NULL;

-- 누가 누구의 코드를 넣었는가. 한 사람은 코드를 한 번만 넣을 수 있다(invitee_id 유니크).
-- created_at 이 코드를 넣은 때다 — 여기서 30 일이 성사 기한이다.
-- rewarded_at 은 초대받은 사람이 첫 빵을 기록해 초대가 성사된 때다. 비어 있으면 아직 기다리는 중이거나 기한이 지난 것이다.
CREATE TABLE member_invites (
    id          BIGSERIAL PRIMARY KEY,
    inviter_id  BIGINT    NOT NULL REFERENCES member (id),
    invitee_id  BIGINT    NOT NULL REFERENCES member (id),
    created_at  TIMESTAMP NOT NULL,
    rewarded_at TIMESTAMP,
    CONSTRAINT uq_member_invites_invitee UNIQUE (invitee_id),
    CONSTRAINT chk_member_invites_not_self CHECK (inviter_id <> invitee_id)
);
CREATE INDEX idx_member_invites_inviter ON member_invites (inviter_id);
