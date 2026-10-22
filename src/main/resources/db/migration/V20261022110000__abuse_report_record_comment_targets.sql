-- 팔로우 · 커뮤니티 P4 (2026-10-22): 빵로그 · 댓글 신고, 신고 3명 자동 숨김, 24시간 미처리 재알림.
-- 자바 enum 과 어긋나 있던 CHECK(RESTRICT_MEMBER · BAN_MEMBER · COURSE)도 같이 맞춘다.

ALTER TABLE abuse_report
    DROP CONSTRAINT IF EXISTS ck_abuse_report_target_type,
    DROP CONSTRAINT IF EXISTS ck_abuse_report_reason_type,
    DROP CONSTRAINT IF EXISTS ck_abuse_report_moderation_action;

ALTER TABLE abuse_report
    ADD CONSTRAINT ck_abuse_report_target_type CHECK (
        target_type IN ('REVIEW', 'MEMBER_PROFILE', 'COURSE', 'RECORD', 'COMMENT')),
    ADD CONSTRAINT ck_abuse_report_reason_type CHECK (
        reason_type IN ('SPAM', 'ABUSE', 'HATE', 'PRIVACY', 'SEXUAL', 'IMPERSONATION', 'ETC')),
    ADD CONSTRAINT ck_abuse_report_moderation_action CHECK (
        moderation_action IN ('NONE', 'HIDE_REVIEW', 'DELETE_REVIEW', 'RESTORE_REVIEW', 'RESET_PROFILE',
            'RESTRICT_MEMBER', 'SUSPEND_MEMBER', 'BAN_MEMBER',
            'HIDE_RECORD', 'RESTORE_RECORD', 'HIDE_COMMENT', 'RESTORE_COMMENT', 'DELETE_COMMENT'));

-- 24시간 미처리 재알림(한 번만)
ALTER TABLE abuse_report ADD COLUMN reminded_at TIMESTAMP;
CREATE INDEX idx_abuse_report_pending_created ON abuse_report (created_at) WHERE status = 'PENDING' AND reminded_at IS NULL;

ALTER TABLE moderation_action
    DROP CONSTRAINT IF EXISTS ck_moderation_action_target_type,
    DROP CONSTRAINT IF EXISTS ck_moderation_action_action;

ALTER TABLE moderation_action
    ADD CONSTRAINT ck_moderation_action_target_type CHECK (
        target_type IN ('REVIEW', 'MEMBER_PROFILE', 'MEMBER_RESTRICTION', 'COURSE', 'RECORD', 'COMMENT')),
    ADD CONSTRAINT ck_moderation_action_action CHECK (
        action IN ('NONE', 'HIDE_REVIEW', 'DELETE_REVIEW', 'RESTORE_REVIEW', 'RESET_PROFILE',
            'RESTRICT_MEMBER', 'SUSPEND_MEMBER', 'BAN_MEMBER',
            'HIDE_RECORD', 'RESTORE_RECORD', 'HIDE_COMMENT', 'RESTORE_COMMENT', 'DELETE_COMMENT'));
