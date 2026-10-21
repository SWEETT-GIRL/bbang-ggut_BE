-- AbuseReport 리네이밍 이후 report_notification.report_kind 신규 값을 허용한다.

UPDATE report_notification
SET report_kind = 'ABUSE_REPORT'
WHERE report_kind = 'USER_REPORT';

ALTER TABLE report_notification
    DROP CONSTRAINT IF EXISTS report_notification_report_kind_check;

ALTER TABLE report_notification
    ADD CONSTRAINT report_notification_report_kind_check CHECK (
        report_kind IN ('STORE_REPORT', 'STORE_ADD_REPORT', 'MENU_ADD_REPORT', 'ABUSE_REPORT')
    );
