-- 팔로우 · 커뮤니티 P2 (2026-10-21): 공개 빵로그 좋아요, 신고 자동 숨김 자리.
-- hidden_* 는 review 와 같은 방식. hidden_by 는 'SYSTEM'(신고 3명 자동) 또는 'ADMIN'.
ALTER TABLE bread_record
    ADD COLUMN hidden_at TIMESTAMP,
    ADD COLUMN hidden_reason VARCHAR(500),
    ADD COLUMN hidden_by VARCHAR(30);

-- 프로필 공개 빵로그 목록(최신순) · 공개 수 · 칭호 집계
CREATE INDEX idx_bread_record_member_public_visible
    ON bread_record (member_id, eaten_at DESC, id DESC)
    WHERE is_public = true AND deleted_at IS NULL AND hidden_at IS NULL;

-- 좋아요. 공개 빵로그에만, 멱등, 부스러기 없음. 비공개로 돌리면 지우지 않고 안 보일 뿐이다.
CREATE TABLE record_like (
    record_id BIGINT NOT NULL REFERENCES bread_record (id),
    member_id BIGINT NOT NULL REFERENCES member (id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    PRIMARY KEY (record_id, member_id)
);
CREATE INDEX idx_record_like_member ON record_like (member_id, created_at DESC);
