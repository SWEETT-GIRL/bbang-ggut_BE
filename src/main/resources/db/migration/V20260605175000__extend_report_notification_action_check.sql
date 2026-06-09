-- report_notification.action 신규 사용자 신고 알림 타입 허용

ALTER TABLE report_notification
    DROP CONSTRAINT IF EXISTS report_notification_action_check;

ALTER TABLE report_notification
    ADD CONSTRAINT report_notification_action_check CHECK (
        action IN ('APPROVED', 'REJECTED', 'USER_REPORT_RESULT', 'MODERATION_ACTION')
    );
