-- 운영 DB 쓰기 감사 로그 — 모든 운영 쓰기는 예외 없이 여기 선(先)기록 후 실행 (롤백 근거)
CREATE TABLE apply_log (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT      NOT NULL,
    target      VARCHAR(60) NOT NULL,
    old_value   JSONB,
    new_value   JSONB,
    origin      VARCHAR(20) NOT NULL,
    proposal_id BIGINT,
    snapshot_id BIGINT,
    applied_at  TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_apply_log_store ON apply_log (store_id, applied_at DESC);
