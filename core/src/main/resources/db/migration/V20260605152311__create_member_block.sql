CREATE TABLE IF NOT EXISTS member_block (
    id BIGSERIAL PRIMARY KEY,
    blocker_id BIGINT NOT NULL REFERENCES member (id),
    blocked_member_id BIGINT NOT NULL REFERENCES member (id),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_member_block_pair UNIQUE (blocker_id, blocked_member_id)
);

CREATE INDEX IF NOT EXISTS idx_member_block_blocker_created_at
    ON member_block (blocker_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_member_block_blocked_member
    ON member_block (blocked_member_id);
