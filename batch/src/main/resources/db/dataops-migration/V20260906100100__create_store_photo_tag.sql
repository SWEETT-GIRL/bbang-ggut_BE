-- 사진 태깅 결과 (Gemini 무료 등급, 인계 노트 §4·§5) — 가게당 1호출 = 1행(prompt_version 별). 로컬 전용, 운영 DB 에는 쓰지 않음.
-- IF NOT EXISTS: 워커 개발 중 psql 로 먼저 만들고, 서버 재기동 시 Flyway 가 같은 파일을 멱등하게 지나가도록.
CREATE TABLE IF NOT EXISTS store_photo_tag (
    store_id            BIGINT      NOT NULL,
    prompt_version      INTEGER     NOT NULL,
    signal_snapshot_id  BIGINT,
    tagger_model        VARCHAR(80),
    status              VARCHAR(20) NOT NULL,           -- DONE / FAILED / SKIPPED(보낼 사진 없음)
    photo_count         INTEGER     NOT NULL DEFAULT 0,
    source_photos       JSONB,                          -- [{idx, source, label, url, sha1, w, h, bytes}] 보낸 사진의 출처·해시
    is_bakery_photo_set BOOLEAN,
    visual_tags         JSONB,                          -- [{tag, confidence}] confidence 0.6 미만 제거 후
    store_tags          JSONB,                          -- [{tag, confidence}] 12태그 + 건강빵·수제
    menu_kinds          JSONB,                          -- [{photoIndex, kind, confidence}] 0.7 이상만
    axis_hint           JSONB,                          -- {taste, texture, filling, vibe} -1~+1
    note                TEXT,
    raw_response        JSONB,                          -- 필터 전 모델 원문 (디버그)
    prompt_tokens       INTEGER,
    response_tokens     INTEGER,
    latency_ms          INTEGER,
    error               TEXT,
    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, prompt_version)
);
CREATE INDEX IF NOT EXISTS idx_store_photo_tag_status ON store_photo_tag (status, prompt_version);
