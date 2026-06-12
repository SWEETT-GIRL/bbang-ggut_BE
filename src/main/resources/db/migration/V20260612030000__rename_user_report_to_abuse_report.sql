-- UserReport 도메인을 AbuseReport로 전환한다.
-- 기존 테이블명은 Flyway 이력 보존을 위해 신규 migration에서 rename한다.

DO $$
BEGIN
    IF to_regclass('public.user_report') IS NOT NULL
        AND to_regclass('public.abuse_report') IS NULL THEN
        ALTER TABLE user_report RENAME TO abuse_report;
    END IF;
END $$;

ALTER TABLE abuse_report
    DROP CONSTRAINT IF EXISTS ck_user_report_target_type,
    DROP CONSTRAINT IF EXISTS ck_user_report_reason_type,
    DROP CONSTRAINT IF EXISTS ck_user_report_status,
    DROP CONSTRAINT IF EXISTS ck_user_report_moderation_action,
    DROP CONSTRAINT IF EXISTS ck_abuse_report_target_type,
    DROP CONSTRAINT IF EXISTS ck_abuse_report_reason_type,
    DROP CONSTRAINT IF EXISTS ck_abuse_report_status,
    DROP CONSTRAINT IF EXISTS ck_abuse_report_moderation_action;

ALTER TABLE abuse_report
    ADD CONSTRAINT ck_abuse_report_target_type CHECK (target_type IN ('REVIEW', 'MEMBER_PROFILE')),
    ADD CONSTRAINT ck_abuse_report_reason_type CHECK (
        reason_type IN ('SPAM', 'ABUSE', 'HATE', 'PRIVACY', 'SEXUAL', 'ETC')
    ),
    ADD CONSTRAINT ck_abuse_report_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    ADD CONSTRAINT ck_abuse_report_moderation_action CHECK (
        moderation_action IN ('NONE', 'HIDE_REVIEW', 'DELETE_REVIEW', 'RESTORE_REVIEW', 'RESET_PROFILE', 'SUSPEND_MEMBER')
    );

ALTER INDEX IF EXISTS uq_user_report_active_target_by_reporter
    RENAME TO uq_abuse_report_active_target_by_reporter;
ALTER INDEX IF EXISTS idx_user_report_status_recent
    RENAME TO idx_abuse_report_status_recent;
ALTER INDEX IF EXISTS idx_user_report_target
    RENAME TO idx_abuse_report_target;
ALTER INDEX IF EXISTS idx_user_report_reported_member
    RENAME TO idx_abuse_report_reported_member;

ALTER TABLE report_notification
    DROP CONSTRAINT IF EXISTS report_notification_action_check;

ALTER TABLE report_notification
    ADD CONSTRAINT report_notification_action_check CHECK (
        action IN ('APPROVED', 'REJECTED', 'ABUSE_REPORT_RESULT', 'MODERATION_ACTION')
    );
