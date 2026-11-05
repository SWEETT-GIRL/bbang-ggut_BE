-- 팔로우 · 커뮤니티 P5 (2026-10-22): 종류별 알림 스위치(기본 켜짐)와 하루 푸시 상한 집계용 인덱스.
ALTER TABLE notification_setting
    ADD COLUMN follow_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN pat_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN like_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN comment_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN following_record_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 하루 상한(톡 · 좋아요 · 팔로잉 소식 5건) — 회원별 오늘 보낸 푸시 수
CREATE INDEX idx_notification_delivery_member_sent ON notification_delivery (member_id, sent_at DESC)
    WHERE channel = 'PUSH' AND status = 'SENT';
