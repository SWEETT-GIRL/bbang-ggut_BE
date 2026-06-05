-- 2026-06-05 — 사용자 신고 결과 및 moderation action 알림 확장
-- 적용 대상: dev DB (soboro @ 3.27.115.144)
--
-- 사용처:
--   - PATCH /api/admin/reports/{reportId} { "kind": "USER_REPORT", "action": "ACCEPT" | "REJECT" }
--   - POST /api/admin/reports/{reportId}/moderation-actions
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-06-05-user-report-notifications.sql
--   또는: scripts/.venv/bin/python scripts/apply_migration.py docs/migrations/2026-06-05-user-report-notifications.sql

ALTER TABLE report_notification
    ADD COLUMN IF NOT EXISTS moderation_action_id BIGINT REFERENCES moderation_action (id);

CREATE INDEX IF NOT EXISTS idx_report_notification_moderation_action
    ON report_notification (moderation_action_id)
    WHERE moderation_action_id IS NOT NULL;
