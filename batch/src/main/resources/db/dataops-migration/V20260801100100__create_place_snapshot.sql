-- 원본층 (불변, append-only) — 해시 동일 시 insert 생략, 보존은 가게당 최신 1개 + 참조분
CREATE TABLE place_snapshot (
    id           BIGSERIAL PRIMARY KEY,
    store_id     BIGINT      NOT NULL,
    source       VARCHAR(20) NOT NULL,
    fetched_at   TIMESTAMP   NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshot_store ON place_snapshot (store_id, source, fetched_at DESC);
