-- 팔로우 · 커뮤니티 P3 (2026-10-22): 빵로그 댓글 · 멘션 · 커뮤니티 규칙 동의.

-- 댓글. 수정 없음(지우고 다시) — 본문이 불변이라 멘션 오프셋이 안 깨진다.
-- deleted_by: AUTHOR(내가) · RECORD_OWNER(빵로그 주인이) · ADMIN. hidden_* 는 bread_record 와 같은 방식(신고 3명 자동 숨김).
CREATE TABLE record_comment (
    id BIGSERIAL PRIMARY KEY,
    record_id BIGINT NOT NULL REFERENCES bread_record (id),
    member_id BIGINT NOT NULL REFERENCES member (id),
    content VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(20),
    hidden_at TIMESTAMP,
    hidden_reason VARCHAR(500),
    hidden_by VARCHAR(30)
);
-- 오래된 순 목록 · 댓글 수
CREATE INDEX idx_record_comment_record_visible ON record_comment (record_id, created_at ASC, id ASC)
    WHERE deleted_at IS NULL AND hidden_at IS NULL;
-- 도배 제한(30초 3개) · 탈퇴 정리
CREATE INDEX idx_record_comment_member_created ON record_comment (member_id, created_at DESC);

-- 멘션. 앱이 고른 사람만(본문 파싱 안 함). 오프셋은 작성 시 본문 기준.
CREATE TABLE comment_mention (
    comment_id BIGINT NOT NULL REFERENCES record_comment (id),
    member_id BIGINT NOT NULL REFERENCES member (id),
    start_index INTEGER NOT NULL,
    length INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    PRIMARY KEY (comment_id, member_id)
);
CREATE INDEX idx_comment_mention_member ON comment_mention (member_id);

-- 커뮤니티 규칙 동의(첫 댓글 · 첫 공개 전환 때). 버전이 오르면 다시 받는다.
ALTER TABLE member
    ADD COLUMN community_agreed_at TIMESTAMP,
    ADD COLUMN community_terms_version VARCHAR(20);
