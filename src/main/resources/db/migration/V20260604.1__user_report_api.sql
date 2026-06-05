-- 사용자 신고 API 저장 구조 추가
-- 기존 DB에는 Flyway baseline 이후 자동 적용되고, 이미 수동 적용된 DB에서도 재실행 가능하도록 IF NOT EXISTS를 유지한다.

CREATE TABLE IF NOT EXISTS user_report (
    id BIGSERIAL PRIMARY KEY,
    reporter_id BIGINT NOT NULL REFERENCES member (id),
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    reported_member_id BIGINT NOT NULL REFERENCES member (id),
    reason_type VARCHAR(30) NOT NULL,
    reason_detail VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    moderation_action VARCHAR(30) NOT NULL DEFAULT 'NONE',
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT ck_user_report_target_type CHECK (target_type IN ('REVIEW', 'MEMBER_PROFILE')),
    CONSTRAINT ck_user_report_reason_type CHECK (
        reason_type IN ('SPAM', 'ABUSE', 'HATE', 'PRIVACY', 'SEXUAL', 'ETC')
    ),
    CONSTRAINT ck_user_report_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_user_report_moderation_action CHECK (
        moderation_action IN ('NONE', 'HIDE_REVIEW', 'DELETE_REVIEW', 'RESET_PROFILE', 'SUSPEND_MEMBER')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_report_active_target_by_reporter
    ON user_report (reporter_id, target_type, target_id)
    WHERE status IN ('PENDING', 'ACCEPTED');

CREATE INDEX IF NOT EXISTS idx_user_report_status_recent
    ON user_report (status, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_user_report_target
    ON user_report (target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_user_report_reported_member
    ON user_report (reported_member_id, created_at DESC);
