-- 실패 검색어 자동 판정(트리아지) 결과. 관리자 처리 상태(status)와 컬럼을 나눠 둔다 —
-- 사람 판정과 비교해 오판율을 재고 자동 처리 임계값을 조정하려면 AI 판정이 따로 남아 있어야 한다.
ALTER TABLE search_misses ADD COLUMN ai_verdict VARCHAR(30);
-- 연결된 대표 가게. 감사·되돌리기용이라 FK 는 걸지 않는다(가게 삭제가 판정 기록에 막히면 안 된다).
ALTER TABLE search_misses ADD COLUMN matched_store_id BIGINT;
-- 판정 단계·네이버 장소·블로그 근거·넣은 alias 등. 화면 표시와 프롬프트 개선용.
ALTER TABLE search_misses ADD COLUMN ai_evidence JSONB;
ALTER TABLE search_misses ADD COLUMN ai_triaged_at TIMESTAMP;
-- 판정 당시 발생 횟수. 이후 두 배 이상 늘면 다시 판정한다.
ALTER TABLE search_misses ADD COLUMN count_at_triage BIGINT;

ALTER TABLE search_misses ADD CONSTRAINT chk_search_misses_ai_verdict CHECK (
    ai_verdict IS NULL OR ai_verdict IN (
        'JUNK', 'NOW_HIT', 'ALIAS_RESOLVED', 'NEW_STORE_CANDIDATE',
        'NOT_A_STORE', 'EXISTS_BUT_DELETED', 'UNRESOLVED'
    )
);

-- 가게명 별칭의 출처. 원래 dataops 가 적재하는 구명 전용이었는데, 실패 검색어 판정이 줄임말·오타를 자동으로 넣는다.
-- 자동 추가분만 골라 일괄 삭제할 수 있도록 구분한다. 기존 행과 dataops INSERT 는 기본값 DATAOPS 가 된다.
ALTER TABLE store_name_alias ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT 'DATAOPS';
ALTER TABLE store_name_alias ADD CONSTRAINT chk_store_name_alias_source CHECK (
    source IN ('DATAOPS', 'SEARCH_MISS_AUTO')
);
