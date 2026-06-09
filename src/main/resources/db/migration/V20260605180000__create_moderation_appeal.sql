CREATE TABLE IF NOT EXISTS moderation_appeal (
    id BIGSERIAL PRIMARY KEY,
    moderation_action_id BIGINT NOT NULL REFERENCES moderation_action (id),
    member_id BIGINT NOT NULL REFERENCES member (id),
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    admin_memo VARCHAR(1000),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uq_moderation_appeal_action_member UNIQUE (moderation_action_id, member_id)
);

CREATE INDEX IF NOT EXISTS idx_moderation_appeal_status_created_at
    ON moderation_appeal (status, created_at DESC, id DESC);
