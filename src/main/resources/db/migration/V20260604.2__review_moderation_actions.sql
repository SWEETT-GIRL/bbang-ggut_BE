-- 리뷰 신고 moderation action 저장 구조 추가
-- 기존 DB에는 Flyway baseline 이후 자동 적용되고, 이미 수동 적용된 DB에서도 재실행 가능하도록 IF NOT EXISTS를 유지한다.

ALTER TABLE review
    ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS hidden_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS hidden_by BIGINT REFERENCES member (id);

CREATE TABLE IF NOT EXISTS moderation_action (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT REFERENCES user_report (id),
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    member_id BIGINT REFERENCES member (id),
    action VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_moderation_action_target_type CHECK (target_type IN ('REVIEW', 'MEMBER_PROFILE')),
    CONSTRAINT ck_moderation_action_action CHECK (
        action IN ('NONE', 'HIDE_REVIEW', 'DELETE_REVIEW', 'RESTORE_REVIEW', 'RESET_PROFILE', 'SUSPEND_MEMBER')
    )
);

ALTER TABLE user_report
    DROP CONSTRAINT IF EXISTS ck_user_report_moderation_action;

ALTER TABLE user_report
    ADD CONSTRAINT ck_user_report_moderation_action CHECK (
        moderation_action IN ('NONE', 'HIDE_REVIEW', 'DELETE_REVIEW', 'RESTORE_REVIEW', 'RESET_PROFILE', 'SUSPEND_MEMBER')
    );

CREATE INDEX IF NOT EXISTS idx_moderation_action_report_recent
    ON moderation_action (report_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_moderation_action_target_recent
    ON moderation_action (target_type, target_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_review_menu_visible_recent
    ON review (menu_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND hidden_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_menu_visible_liked
    ON review (menu_id, like_count DESC, created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND hidden_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_member_visible_recent
    ON review (member_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND hidden_at IS NULL;
