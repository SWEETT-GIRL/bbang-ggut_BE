-- 2026-06-05 — report_notification.action 신규 알림 타입 허용
-- 적용 대상: 운영 DB (soboro)
--
-- 증상:
--   report_notification_action_check 제약이 USER_REPORT_RESULT, MODERATION_ACTION 을 허용하지 않아
--   사용자 신고 승인/거절 처리 중 신고자 알림 저장이 실패한다.
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-06-05-report-notification-action-check.sql
--   또는: scripts/.venv/bin/python scripts/apply_migration.py docs/migrations/2026-06-05-report-notification-action-check.sql

ALTER TABLE report_notification
    DROP CONSTRAINT IF EXISTS report_notification_action_check;

ALTER TABLE report_notification
    ADD CONSTRAINT report_notification_action_check CHECK (
        action IN ('APPROVED', 'REJECTED', 'USER_REPORT_RESULT', 'MODERATION_ACTION')
    );
