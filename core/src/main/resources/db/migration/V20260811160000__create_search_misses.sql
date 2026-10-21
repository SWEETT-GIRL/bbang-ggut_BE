-- 결과를 못 낸 검색어. 관리자가 보고 없는 가게를 추가하기 위한 작업 목록.
-- 지표(search_query_total)는 "얼마나 자주"에 답하지만 검색어를 담을 수 없어(카디널리티 무한)
-- "무슨 검색어였나"는 이 테이블이 맡는다.
CREATE TABLE search_misses (
    id                  BIGSERIAL       PRIMARY KEY,
    -- 공백·특수문자를 제거한 중복 제거 키. "성수동 크로플"과 "성수동크로플"을 한 행으로 묶는다.
    normalized_keyword  VARCHAR(100)    NOT NULL UNIQUE,
    -- 마지막으로 들어온 사용자 입력 원문. 관리자 화면 표시용.
    keyword             VARCHAR(100)    NOT NULL,
    -- 마지막 발생 시 판정 결과. EMPTY는 "진짜 없는 가게" 후보,
    -- MISMATCH/MISMATCH_LOOSE는 "있는데 검색이 못 찾은 것"일 가능성이 높다.
    outcome             VARCHAR(30)     NOT NULL,
    occurrence_count    BIGINT          NOT NULL DEFAULT 1,
    first_occurred_at   TIMESTAMP       NOT NULL,
    last_occurred_at    TIMESTAMP       NOT NULL,
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- 처리 시각. 90일 경과 건 삭제 기준이며, PENDING은 NULL이라 삭제 대상에서 자연히 빠진다.
    handled_at          TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP,
    -- HIT은 적재하지 않는다. 성공한 검색은 처리할 것이 없다.
    CONSTRAINT chk_search_misses_outcome CHECK (
        outcome IN ('EMPTY', 'FALLBACK', 'MISMATCH', 'MISMATCH_LOOSE')
    ),
    CONSTRAINT chk_search_misses_status CHECK (
        status IN ('PENDING', 'STORE_ADDED', 'ALREADY_EXISTS', 'IGNORED')
    ),
    -- 처리 상태와 처리 시각은 항상 같이 움직인다.
    CONSTRAINT chk_search_misses_handled_at CHECK (
        (status = 'PENDING' AND handled_at IS NULL)
        OR (status <> 'PENDING' AND handled_at IS NOT NULL)
    )
);

-- 관리자 기본 목록: PENDING을 발생 횟수 많은 순으로.
CREATE INDEX idx_search_misses_status_count
    ON search_misses (status, occurrence_count DESC);

-- 최근 발생순 조회.
CREATE INDEX idx_search_misses_status_last_occurred
    ON search_misses (status, last_occurred_at DESC);
