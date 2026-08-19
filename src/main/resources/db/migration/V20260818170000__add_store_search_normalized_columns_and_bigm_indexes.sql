-- 가게 검색을 OpenSearch → PostgreSQL 로 옮기기 위한 스키마 (2026-08).
-- 검색 대상은 가게명·구명(store_name_alias)·지역(store_search_region/search_region_alias)·주소(폴백)로 한정한다.
--
-- pg_bigm: 2-gram GIN 으로 LIKE '%키워드%' 부분일치가 인덱스를 타게 한다. 한국어 2글자 검색어(몽심·대구·성심)가 많아
-- pg_trgm(3-gram, 2글자는 seq scan) 대신 택했다. RDS PostgreSQL 18 에서 1.2 제공 (2026-08-18 랩 실측 기록은
-- .claude/planning/feat-search-postgres-bigm.md 참고).
CREATE EXTENSION IF NOT EXISTS pg_bigm;

-- 정규화 컬럼은 STORED 생성 컬럼으로 두어 앱 코드가 값을 유지할 필요가 없게 한다.
-- 정규화 규칙(lower + [^0-9a-z가-힣] 제거)은 OpenSearch 의 query_normalized_analyzer, 파서·엔진의 Java 정규화와 동일해야 한다.
ALTER TABLE store
    ADD COLUMN IF NOT EXISTS name_normalized varchar(100)
        GENERATED ALWAYS AS (regexp_replace(lower(name), '[^0-9a-z가-힣]', '', 'g')) STORED,
    ADD COLUMN IF NOT EXISTS address_normalized varchar(255)
        GENERATED ALWAYS AS (regexp_replace(lower(address), '[^0-9a-z가-힣]', '', 'g')) STORED,
    -- 인기도(구독수 + 전국 랭킹 점수 + 조회수). OpenSearch 문서의 popularityScore 를 RDB 로 옮긴 것.
    ADD COLUMN IF NOT EXISTS popularity_score double precision NOT NULL DEFAULT 0;

-- 원본 alias 가 VARCHAR(255) 이므로 생성 컬럼도 같은 길이 — 더 짧게 잡으면 긴 alias INSERT 가 생성 컬럼에서 실패한다.
ALTER TABLE store_name_alias
    ADD COLUMN IF NOT EXISTS alias_normalized varchar(255)
        GENERATED ALWAYS AS (regexp_replace(lower(alias), '[^0-9a-z가-힣]', '', 'g')) STORED;
-- 이 마이그레이션의 초안(varchar(100)·접두 인덱스 포함)이 로컬 부팅으로 운영에 먼저 적용되고 이력만 지워진 일이 있어(2026-08-19),
-- 이미 적용된 환경에서 다시 돌아도 같은 결과가 되도록 보정 문장을 함께 둔다. 처음 적용되는 환경에서는 no-op 이다.
ALTER TABLE store_name_alias ALTER COLUMN alias_normalized TYPE varchar(255);
DROP INDEX IF EXISTS idx_store_name_normalized_prefix;
DROP INDEX IF EXISTS idx_store_name_alias_normalized_prefix;
DROP INDEX IF EXISTS idx_search_region_alias_normalized_prefix;

-- 부분일치(검색) — bigm GIN
CREATE INDEX IF NOT EXISTS idx_store_name_normalized_bigm ON store USING gin (name_normalized gin_bigm_ops);
CREATE INDEX IF NOT EXISTS idx_store_address_normalized_bigm ON store USING gin (address_normalized gin_bigm_ops);
CREATE INDEX IF NOT EXISTS idx_store_name_alias_normalized_bigm ON store_name_alias USING gin (alias_normalized gin_bigm_ops);
-- 자동완성도 '%q%'(bigm) 로 후보를 뽑고 접두 여부는 정렬(tier)에만 쓰므로 별도 접두(text_pattern_ops) 인덱스는 두지 않는다.
-- 지역 alias 는 검색 조건이 정확 일치(normalized_alias IN …)라 기존 uq/btree 로 충분하다.
-- 지역만 검색("서울")처럼 후보가 1만 건을 넘는 경우의 인기순 정렬용
CREATE INDEX IF NOT EXISTS idx_store_popularity_score ON store (popularity_score DESC);

-- 인기도 1회 백필. 이후는 주간 배치(StorePopularityScoreScheduler)와 어드민 수동 갱신이 같은 산식으로 덮어쓴다.
-- 산식(StorePopularityRepository.refreshAllPopularityScores 와 동일): 구독수 + Σ max(0, 51-rank) + STORE_VIEW 조회수
UPDATE store s
SET popularity_score = COALESCE(sub.cnt, 0) + COALESCE(rk.score, 0) + COALESCE(vw.cnt, 0)
FROM store st
LEFT JOIN (SELECT store_id, count(*)::float8 AS cnt FROM subscribed_store GROUP BY store_id) sub ON sub.store_id = st.id
LEFT JOIN (SELECT store_id, sum(greatest(0, 51 - rank))::float8 AS score FROM nationwide_hot_store_ranking GROUP BY store_id) rk ON rk.store_id = st.id
LEFT JOIN (SELECT store_id, count(*)::float8 AS cnt FROM request_log WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL GROUP BY store_id) vw ON vw.store_id = st.id
WHERE s.id = st.id
  -- 값이 실제로 바뀌는 행만 — 재실행(운영 선적용 보정) 시 45k행 전량 재기록을 피한다
  AND s.popularity_score IS DISTINCT FROM (COALESCE(sub.cnt, 0) + COALESCE(rk.score, 0) + COALESCE(vw.cnt, 0));
