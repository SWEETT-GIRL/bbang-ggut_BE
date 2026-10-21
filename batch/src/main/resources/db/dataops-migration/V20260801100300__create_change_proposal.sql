-- 자동 반영 불가 diff 의 수납함 — 같은 대상의 PENDING 은 1건만 (새 diff 는 기존 건 SUPERSEDED 후 insert)
CREATE TABLE change_proposal (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT      NOT NULL,
    target      VARCHAR(60) NOT NULL,
    old_value   JSONB,
    new_value   JSONB,
    source      VARCHAR(20) NOT NULL,
    snapshot_id BIGINT,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    review_note VARCHAR(500),
    reviewed_at TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP   DEFAULT now()
);
CREATE UNIQUE INDEX uq_proposal_pending ON change_proposal (store_id, target) WHERE status = 'PENDING';
CREATE INDEX idx_proposal_status ON change_proposal (status, created_at);
