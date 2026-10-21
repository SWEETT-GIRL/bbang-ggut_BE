-- 가게 신호(태그 소스) — 가게별 최신 스냅샷을 StoreSignalParser 로 재파싱한 결과. 가게당 1행, 재빌드로 덮어쓴다.
-- 원본은 place_snapshot 에 있으므로 파서가 바뀌면 rebuild-all 로 복구 가능 (parser_version 으로 세대 구분).
-- 스칼라 컬럼은 조회·집계용 요약, 나머지(votedKeywords·reviewMenus·recommendMenus·topPhotos …)는 signals JSONB.
CREATE TABLE store_signal (
    store_id              BIGINT           PRIMARY KEY,
    snapshot_id           BIGINT           NOT NULL,
    fetched_at            TIMESTAMP        NOT NULL,
    category              VARCHAR(100),
    visitor_reviews_score DOUBLE PRECISION,
    visitor_reviews_total INTEGER          NOT NULL DEFAULT 0,
    recommend_menu_count  INTEGER          NOT NULL DEFAULT 0,
    new_opening           BOOLEAN          NOT NULL DEFAULT FALSE,
    signals               JSONB            NOT NULL,
    parser_version        INTEGER          NOT NULL,
    created_at            TIMESTAMP        NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP        NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_signal_category ON store_signal (category);
