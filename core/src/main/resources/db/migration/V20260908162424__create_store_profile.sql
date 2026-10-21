-- 가게 취향 프로필 — dataops 배치 산출물 (취향 추천 설계 v2.2 §4 · §5.1, 이슈 #659).
--
-- 쓰는 쪽은 dataops(ServingWriter) 하나뿐이다. BE 는 읽기만 한다 (엔티티는 @Immutable).
-- 처음 설계에는 BE 가 갱신하는 conversion_prior(전환율 prior)가 이 행에 있었지만 09-07 검토에서 뺐다 — 한 행을 두 주체가
-- 쓰면 증분 upsert 와 JPA 저장이 서로 덮어쓴다. 인기 prior 는 BE 소유 store.popularity_score(주간 배치)와
-- 이 표 attrs 의 네이버 리뷰 수·평점을 #661 후보 SQL 이 요청 때 합쳐 쓴다. 전환율 항은 #663 이 popularity 산식에 더한다.
--
-- 이 표가 비어 있어도 추천은 돌아가야 한다: 축이 없으면 취향 항을 빼고 인기 순으로 내려간다.
--
-- 축 4개는 menu_category 와 같은 이름·같은 부호 약속(A극 -1 / B극 +1)이고, 값은 메뉴 축의 가중 평균이라 -1~+1 소수다.
--   axis = 0.6 × 운영 분류 메뉴 축(좋아요 수 가중) + 0.4 × 리뷰 언급 메뉴 축(언급 수 가중)
--   메뉴 3개 미만이면 리뷰만으로도 허용(라벨 3개↑ 또는 언급 20회↑). 넷 다 NULL 이면 축 근거 부족.
--   axis_source 가 어느 쪽에서 왔는지 남긴다 (MENU | REVIEW | BOTH).
-- tags 의 키는 설계 §2 취향 태그 12개의 한글 이름 그대로다 (카페형·테이크아웃·노포·신상·대형·동네빵집·데이트·혼빵·선물·
--   웨이팅감수·아침·저녁). LLM 프롬프트 사전(§7.2)과 같은 문자열이라 변환표가 없다. BE 는 같은 12개를 enum 으로 고정한다.
-- bakery_score 는 "빵집다움" (0.5·category + 0.3·빵 종류 메뉴 비중 + 0.2·bread_good 비율). 후보 필터는 0.5 이상만.
-- store_status 는 계산 시점의 store.status 스냅샷. 후보 SQL 은 store.status 를 본다.
--
-- 증분 갱신: PK 로 ON CONFLICT upsert, computed_at 이 행별 신선도, batch_version 이 산식 버전이다.
-- 중심화·매핑표 검수처럼 산식이 바뀌면 WHERE batch_version <> '새 버전' 으로 재계산 대상을 고른다.
CREATE TABLE store_profile (
    store_id         BIGINT        PRIMARY KEY REFERENCES store (id),
    axis_taste       NUMERIC(4, 3),
    axis_texture     NUMERIC(4, 3),
    axis_filling     NUMERIC(4, 3),
    axis_vibe        NUMERIC(4, 3),
    axis_source      VARCHAR(10),                  -- MENU | REVIEW | BOTH
    tags             JSONB         NOT NULL DEFAULT '{}',   -- 취향 태그 12 (0~1): {"카페형": 0.9, "혼빵": 0.6}
    attrs            JSONB         NOT NULL DEFAULT '{}',   -- 편의·유형 속성 + prior 재료: {"parking": true, "category": "CAKE", "reviewCount": 312}
    kinds            TEXT[],                       -- 메뉴 ∪ 리뷰 언급 종류 (사용자 top_kinds 와 겹침 계산)
    signature_menus  TEXT[],                       -- 대표 메뉴 (recommend + 리뷰 언급 상위, 최대 5)
    bakery_score     NUMERIC(4, 3) NOT NULL DEFAULT 0,
    store_status     VARCHAR(10),
    tagger_model     VARCHAR(60),                  -- LLM 태그·사진 태깅 출처
    prompt_version   VARCHAR(20),
    batch_version    VARCHAR(20)   NOT NULL,       -- 축·태그 산식 버전 (dataops 가 적는다)
    computed_at      TIMESTAMP     NOT NULL,       -- KST 벽시계 (이 DB 의 TIMESTAMP 약속)
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP,
    CONSTRAINT ck_store_profile_axis_source CHECK (axis_source IS NULL OR axis_source IN ('MENU', 'REVIEW', 'BOTH')),
    CONSTRAINT ck_store_profile_axis_range CHECK (
        (axis_taste   IS NULL OR axis_taste   BETWEEN -1 AND 1) AND
        (axis_texture IS NULL OR axis_texture BETWEEN -1 AND 1) AND
        (axis_filling IS NULL OR axis_filling BETWEEN -1 AND 1) AND
        (axis_vibe    IS NULL OR axis_vibe    BETWEEN -1 AND 1)),
    CONSTRAINT ck_store_profile_bakery_score CHECK (bakery_score BETWEEN 0 AND 1)
);

-- dataops 가 채우고, reader 로 읽는다. 롤이 없는 환경(로컬·CI·랩)에서는 조용히 건너뛴다. PK 가 store_id 라 시퀀스 GRANT 불요.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_writer') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON store_profile TO dataops_writer;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_reader') THEN
        GRANT SELECT ON store_profile TO dataops_reader;
    END IF;
END $$;
