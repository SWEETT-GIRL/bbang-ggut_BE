-- 검색어 교정: "이 검색어는 사실 저 검색어다"를 한 줄로 둔다. 검색·자동완성 앞단에서 바꿔 돌린다.
-- 실패 검색어 자동 판정이 오타·줄임말·별명을 찾으면 여기에 넣는다(가게별 별칭 대신 — 체인도 한 줄, 새 지점 자동 포함).
-- dataops 의 구명 별칭(store_name_alias)과는 용도가 다르다.
CREATE TABLE search_keyword_correction (
    id                  BIGSERIAL       PRIMARY KEY,
    -- 검색과 같은 정규화(소문자, [^0-9a-z가-힣] 제거). 검색어 전체·단어 단위 조회 키
    normalized_keyword  VARCHAR(100)    NOT NULL UNIQUE,
    -- 원문. 관리·리포트 표시용
    keyword             VARCHAR(100)    NOT NULL,
    -- 바꿔 돌릴 검색어
    corrected_query     VARCHAR(255)    NOT NULL,
    source              VARCHAR(30)     NOT NULL,
    -- 어느 실패 검색어에서 나왔나. search_misses 는 처리 90일 뒤 지워지므로 FK 를 걸지 않는다 — 교정은 남아야 한다
    search_miss_id      BIGINT,
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP,
    CONSTRAINT chk_search_keyword_correction_source CHECK (source IN ('SEARCH_MISS_AUTO', 'ADMIN'))
);

-- 판정명 변경: 별칭 대신 교정으로 처리한다. shadow 로 판정만 된 기존 행도 같이 바꾼다.
ALTER TABLE search_misses DROP CONSTRAINT chk_search_misses_ai_verdict;
UPDATE search_misses SET ai_verdict = 'CORRECTED' WHERE ai_verdict = 'ALIAS_RESOLVED';
ALTER TABLE search_misses ADD CONSTRAINT chk_search_misses_ai_verdict CHECK (
    ai_verdict IS NULL OR ai_verdict IN (
        'JUNK', 'NOW_HIT', 'CORRECTED', 'NEW_STORE_CANDIDATE',
        'NOT_A_STORE', 'EXISTS_BUT_DELETED', 'UNRESOLVED'
    )
);
