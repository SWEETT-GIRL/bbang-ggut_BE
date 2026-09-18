-- 뜨는 디저트와 그걸 파는 곳 — 디저트 파이프라인 산출물.
--
-- 파이프라인(블로그 검색 → LLM 가게명 추출 → 카카오·네이버 매칭)이 쓰고 BE 는 읽기만 한다. store_profile 과 같은 구조다.
-- 디저트는 세 종류다.
--   CONVENIENCE: 편의점 상품. 브랜드 전체에서 팔아서 가게 단위 연결이 없다 → 지도는 convenience_store 의 해당 브랜드 지점을 보여준다.
--   CHAIN      : 체인 한정 메뉴. 마찬가지로 브랜드 단위.
--   GENERIC    : 피자설기처럼 개별 가게가 파는 디저트. 가게마다 파는지가 달라서 dessert_item_store 로 연결한다.

CREATE TABLE dessert_item (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(50)  NOT NULL UNIQUE,
    aliases       TEXT[]       NOT NULL DEFAULT '{}',   -- 검색 별칭: {피자설기, 피자 설기}. 수집 스크립트가 이 값으로 검색한다
    item_type     VARCHAR(20)  NOT NULL,
    brand         VARCHAR(50),                           -- CONVENIENCE·CHAIN 의 판매 브랜드 (CU, 스타벅스 …)
    trend_status  VARCHAR(20),                           -- dessert-radar 판정: HOT | RISING | COOLING | UNCERTAIN
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,     -- 수집·노출 대상 여부
    launched_at   DATE,
    ended_at      DATE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP,
    CONSTRAINT ck_dessert_item_type CHECK (item_type IN ('CONVENIENCE', 'CHAIN', 'GENERIC')),
    CONSTRAINT ck_dessert_item_brand CHECK (item_type = 'GENERIC' OR brand IS NOT NULL),
    CONSTRAINT ck_dessert_item_trend_status CHECK (
        trend_status IS NULL OR trend_status IN ('HOT', 'RISING', 'COOLING', 'UNCERTAIN'))
);

-- 편의점 지점. 빵긋 가게(store)와 섞지 않으려고 따로 둔다.
-- 전국 5만 곳을 미리 넣지 않고, 지도를 연 지역만 카카오 편의점 카테고리(CS2)에서 가져와 쌓는 캐시로 쓴다.
CREATE TABLE convenience_store (
    id              BIGSERIAL              PRIMARY KEY,
    brand           VARCHAR(20)            NOT NULL,
    branch_name     VARCHAR(100)           NOT NULL,
    address         VARCHAR(255)           NOT NULL,
    location        geography(Point, 4326) NOT NULL,
    kakao_place_id  VARCHAR(50)            NOT NULL UNIQUE,
    created_at      TIMESTAMP              NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    CONSTRAINT ck_convenience_store_brand CHECK (brand IN ('CU', 'GS25', 'SEVEN_ELEVEN', 'EMART24'))
);

CREATE INDEX idx_convenience_store_location ON convenience_store USING GIST (location);

-- 이 가게에서 이 디저트를 판다 (GENERIC 전용). 지도는 이 표와 store 를 조인해 반경 조회한다.
-- grade·score 는 근거(dessert_item_evidence)에서 파이프라인이 매일 다시 계산한다. 시간이 지나면 등급이 내려가야 해서다.
CREATE TABLE dessert_item_store (
    dessert_item_id    BIGINT        NOT NULL REFERENCES dessert_item (id),
    store_id           BIGINT        NOT NULL REFERENCES store (id),
    grade              VARCHAR(20)   NOT NULL,           -- STRONG(최근 후기 여럿) | LIKELY | WEAK(근거 약함) | STALE(오래됨)
    score              NUMERIC(6, 2) NOT NULL,
    sale_type          VARCHAR(20)   NOT NULL DEFAULT 'REGULAR',
    status             VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    evidence_count     INT           NOT NULL,
    first_evidence_at  DATE          NOT NULL,
    last_evidence_at   DATE          NOT NULL,           -- "최근 언급 3일 전" 표시용
    computed_at        TIMESTAMP     NOT NULL,           -- KST 벽시계 (이 DB 의 TIMESTAMP 약속)
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP,
    PRIMARY KEY (dessert_item_id, store_id),
    CONSTRAINT ck_dessert_item_store_grade CHECK (grade IN ('STRONG', 'LIKELY', 'WEAK', 'STALE')),
    CONSTRAINT ck_dessert_item_store_sale_type CHECK (sale_type IN ('REGULAR', 'POPUP', 'ONLINE')),
    CONSTRAINT ck_dessert_item_store_status CHECK (status IN ('ACTIVE', 'SOLD_OUT_REPORTED', 'ENDED'))
);

CREATE INDEX idx_dessert_item_store_store_id ON dessert_item_store (store_id);

-- 근거 한 건 한 건 (블로그 글 하나가 가게 여럿을 언급하면 여러 행).
-- 매칭에 실패한 언급도 store_id NULL 로 남긴다 → 운영 검토 대기열이자, 매칭 로직을 고친 뒤 다시 돌릴 원재료.
-- 블로그 본문은 저장하지 않는다 (저작권). 링크와 제목만 남긴다.
CREATE TABLE dessert_item_evidence (
    id                 BIGSERIAL     PRIMARY KEY,
    dessert_item_id    BIGINT        NOT NULL REFERENCES dessert_item (id),
    store_id           BIGINT        REFERENCES store (id),
    source_type        VARCHAR(20)   NOT NULL,
    source_url         VARCHAR(500)  NOT NULL,
    title              VARCHAR(200),
    posted_at          DATE          NOT NULL,
    mention_kind       VARCHAR(10)   NOT NULL,           -- REVIEW(그 가게 단독 후기) | LIST(총정리·뉴스 속 언급)
    extracted_name     VARCHAR(100)  NOT NULL,           -- LLM 이 뽑은 원래 값
    extracted_area     VARCHAR(100),
    extracted_address  VARCHAR(255),
    match_method       VARCHAR(20),                      -- 어떻게 store 에 붙였는지. 오매칭 추적용
    match_score        NUMERIC(4, 3),
    unmatched_reason   VARCHAR(50),                      -- store_id 가 NULL 일 때만: 지역 없음 | 동명 가게 여러 곳 | 매칭 실패
    collected_at       TIMESTAMP     NOT NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dessert_item_evidence UNIQUE (dessert_item_id, source_url, extracted_name),
    CONSTRAINT ck_dessert_item_evidence_source_type CHECK (source_type IN ('BLOG', 'USER_REPORT', 'OWNER')),
    CONSTRAINT ck_dessert_item_evidence_mention_kind CHECK (mention_kind IN ('REVIEW', 'LIST')),
    CONSTRAINT ck_dessert_item_evidence_match_method CHECK (
        match_method IS NULL OR match_method IN ('ADDRESS', 'NAME_AREA', 'LANDMARK', 'NAVER', 'BLOG_ADDRESS')),
    CONSTRAINT ck_dessert_item_evidence_matched CHECK ((store_id IS NULL) = (match_method IS NULL))
);

CREATE INDEX idx_dessert_item_evidence_store_id ON dessert_item_evidence (store_id);
CREATE INDEX idx_dessert_item_evidence_unmatched ON dessert_item_evidence (dessert_item_id) WHERE store_id IS NULL;

-- 파이프라인이 dataops_writer 롤로 쓴다. 롤이 없는 환경(로컬·CI·랩)에서는 조용히 건너뛴다.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_writer') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE
            ON dessert_item, convenience_store, dessert_item_store, dessert_item_evidence TO dataops_writer;
        GRANT USAGE, SELECT
            ON SEQUENCE dessert_item_id_seq, convenience_store_id_seq, dessert_item_evidence_id_seq TO dataops_writer;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_reader') THEN
        GRANT SELECT ON dessert_item, convenience_store, dessert_item_store, dessert_item_evidence TO dataops_reader;
    END IF;
END $$;
