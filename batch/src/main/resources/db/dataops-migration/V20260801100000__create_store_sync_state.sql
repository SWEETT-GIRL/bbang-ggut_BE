-- 가게×소스 동기화 상태 — 이 테이블이 곧 워크리스트다 (별도 작업 큐 없음)
-- store_id 는 운영 store.id 논리 참조 (FK 없음), external_id 는 운영 naver_place_id 의 복사본
CREATE TABLE store_sync_state (
    id                   BIGSERIAL PRIMARY KEY,
    store_id             BIGINT      NOT NULL,
    source               VARCHAR(20) NOT NULL,
    external_id          VARCHAR(80),
    tier                 VARCHAR(10) NOT NULL DEFAULT 'T1',
    state                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    next_due_at          TIMESTAMP,
    last_attempt_at      TIMESTAMP,
    last_success_at      TIMESTAMP,
    content_hash         VARCHAR(64),
    visit_count          INT NOT NULL DEFAULT 0,
    change_count         INT NOT NULL DEFAULT 0,
    consecutive_failures INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMP NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP DEFAULT now(),
    UNIQUE (store_id, source)
);
CREATE INDEX idx_sync_state_due ON store_sync_state (next_due_at) WHERE state = 'ACTIVE';
CREATE INDEX idx_sync_state_store ON store_sync_state (store_id);
