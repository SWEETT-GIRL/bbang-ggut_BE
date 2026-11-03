-- 푸시 묶음 대기열. 같은 사람에게 같은 사람이 보낸 같은 종류(팔로잉 새 빵로그 · 좋아요)는 창(10분) 동안 모았다가
-- 한 번에 울린다. 내보내면 지우므로 늘 작다. 알림함(notification_recipient)은 건마다 그대로 남는다.
CREATE TABLE push_batch_item (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT       NOT NULL,
    notification_id BIGINT       NOT NULL,
    type            VARCHAR(40)  NOT NULL,
    actor_id        BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_push_batch_item_member_notification UNIQUE (member_id, notification_id)
);

CREATE INDEX idx_push_batch_item_group ON push_batch_item (member_id, type, actor_id, created_at);
