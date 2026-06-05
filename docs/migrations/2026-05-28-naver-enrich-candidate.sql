-- 2026-05-28 — NaverEnrichCandidate 테이블 생성
-- 적용 대상: dev DB (soboro @ 3.27.115.144)
-- 사용처: 매칭 실패한 가게의 네이버 검색 후보를 보관해서 어드민이 '후보 보기' UI 에서 선택 가능하게.
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-05-28-naver-enrich-candidate.sql
-- 또는 SQL 클라이언트에서 본 파일 내용을 그대로 실행.
--
-- 적용 후 backend 재기동 — local 프로파일 ddl-auto=validate 통과 확인.

CREATE TABLE IF NOT EXISTS naver_enrich_candidate (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT      NOT NULL,
    naver_place_id  VARCHAR(50) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    address         VARCHAR(500),
    lat             DOUBLE PRECISION,
    lng             DOUBLE PRECISION,
    display_order   INTEGER     NOT NULL,
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP,
    CONSTRAINT uk_naver_enrich_candidate_store_place UNIQUE (store_id, naver_place_id)
);

CREATE INDEX IF NOT EXISTS idx_naver_enrich_candidate_store
    ON naver_enrich_candidate (store_id);
