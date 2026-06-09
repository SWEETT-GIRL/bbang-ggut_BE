-- 사용자 신고 결과 및 moderation action 알림 확장

ALTER TABLE report_notification
    ADD COLUMN IF NOT EXISTS moderation_action_id BIGINT REFERENCES moderation_action (id);

CREATE INDEX IF NOT EXISTS idx_report_notification_moderation_action
    ON report_notification (moderation_action_id)
    WHERE moderation_action_id IS NOT NULL;
