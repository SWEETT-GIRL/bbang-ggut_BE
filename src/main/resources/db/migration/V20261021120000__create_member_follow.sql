-- 팔로우 (2026-10-21, 팔로우 · 커뮤니티 P1). 한쪽이 누르면 끝나는 단방향 관계라 행 하나가 "follower 가 followee 를 팔로우한다"다.
-- 차단하면 양방향을 지우고 해제해도 되살리지 않는다. 탈퇴하면 양방향을 지운다.
CREATE TABLE member_follow (
    follower_id BIGINT NOT NULL REFERENCES member (id),
    followee_id BIGINT NOT NULL REFERENCES member (id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT ck_member_follow_not_self CHECK (follower_id <> followee_id)
);

-- 팔로워 목록 · 팔로워 수
CREATE INDEX idx_member_follow_followee_created ON member_follow (followee_id, created_at DESC);
-- 팔로잉 목록 · 하루 팔로우 횟수(created_at 범위)
CREATE INDEX idx_member_follow_follower_created ON member_follow (follower_id, created_at DESC);
