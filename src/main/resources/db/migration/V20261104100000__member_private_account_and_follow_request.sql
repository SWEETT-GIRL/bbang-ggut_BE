-- 비공개 계정(인스타식, 2026-11-04). 팔로워만 공개 빵로그를 본다. 프로필(닉네임 · 캐릭터 · 수)은 누구나 본다.
ALTER TABLE member ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT false;

-- 비공개 계정에 보낸 팔로우 요청. 받은 사람이 수락하면 member_follow 로 옮기고 지운다.
-- 거절 · 요청 취소 · 차단 · 탈퇴 · 공개 전환(전부 수락)이면 지운다. 늘 작다.
CREATE TABLE member_follow_request (
    requester_id BIGINT    NOT NULL REFERENCES member (id),
    target_id    BIGINT    NOT NULL REFERENCES member (id),
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP,
    PRIMARY KEY (requester_id, target_id),
    CONSTRAINT ck_member_follow_request_not_self CHECK (requester_id <> target_id)
);

-- 받은 요청 목록(최신순) · 받은 요청 수
CREATE INDEX idx_member_follow_request_target_created ON member_follow_request (target_id, created_at DESC);
