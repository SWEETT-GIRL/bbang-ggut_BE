-- 사용자 취향 신호와 프로필 (취향 추천 설계 v2.2 §3 · §4 · §5.1, 이슈 #659). 채우는 배치는 #663.
--
-- user_store_interaction: 사용자가 가게·빵에 남긴 신호 한 건 = 행 한 개. 사실만 적고 해석은 배치가 한다.
--   매일 04:00 배치가 bread_record·saved_stores·course_stores·store_plan·subscribed_store·request_log(STORE_VIEW) 와
--   Amplitude 백필(저장 해제), recommendation_feedback 에서 전날분을 적재한다.
--   occurred_at 은 사건 시각이고 created_at 은 적재 시각이다 — 반감기(90일)는 사건 시각 기준이어야 한다.
--   같은 사건을 두 번 적재하지 않도록 (member, type, store, menu, occurred_at) 을 유일하게 둔다. 배치 재실행이 안전해진다.
--   가중치(설계 §3.1)는 저장하지 않는다 — 반감기·선택 성향 보정이 시점마다 달라 어차피 계산 때 곱해야 하고, 유형별 기본
--   가중치는 type 과 1:1 이라 #663 의 InteractionType enum 이 갖는다. 페르소나 검사로 가중치를 바꿔도 재적재가 필요 없다.
--   menu_id 는 기록의 빵이 가리키는 가게 메뉴다 (기록 흐름이 가게 메뉴를 만들거나 고르므로 이름으로 100% 찾힌다).
--   종류(menu_category_id)는 여기 복사하지 않고 배치가 menu 와 조인해 읽는다 — 회원 생성 메뉴의 종류 부여와 2차 재분류가
--   예정돼 있어, 복사하면 적재 시점의 옛 종류가 굳는다. 메뉴 단위 신호는 기록의 빵뿐이다 (메뉴 상세 화면은 앱에서 사라졌다).
--   type 값 체계는 #663 의 enum 이 정한다. 로그성 테이블이라 FK 는 두지 않는다.
CREATE TABLE user_store_interaction (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL,
    store_id    BIGINT,
    menu_id     BIGINT,                             -- 기록의 빵이 가리키는 가게 메뉴. 가게 단위 신호면 NULL
    type        VARCHAR(24) NOT NULL,               -- RECORD_BREAD, RECORD_STORE, SAVE, PLAN, COURSE_ADD, STORE_VIEW, … (#663)
    context     VARCHAR(24),                        -- 어디서 (예: CURATION, SEARCH, RECO)
    converted   BOOLEAN,                            -- 같은 세션에서 저장·담기로 이어졌는지
    occurred_at TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_store_interaction UNIQUE NULLS NOT DISTINCT (member_id, type, store_id, menu_id, occurred_at)
);

-- 프로필 계산은 "한 사람의 최근 N일" 한 가지 모양이다.
CREATE INDEX idx_user_store_interaction_member_occurred
    ON user_store_interaction (member_id, occurred_at DESC);

-- 전환율 항(#663, popularity 산식)은 가게별로 센다.
CREATE INDEX idx_user_store_interaction_store
    ON user_store_interaction (store_id)
    WHERE store_id IS NOT NULL;

-- user_taste_profile: 사용자 한 명당 한 행. 축 4개는 store_profile 과 같은 이름·부호 약속(A극 -1 / B극 +1).
--   축 = 신호 가중 평균 + 검사 응답 × 3. 검사 응답 시 그 사용자만 즉시 재계산, 나머지는 배치.
--   type_code 는 네 축의 부호로 정한 16타입 코드(#660 BbtiType), type_source 는 그 타입이 검사(QUIZ)에서 왔는지 신호(SIGNAL)에서 왔는지.
--   quiz_answered_at 은 마지막으로 검사를 푼 시각 — 타입 카드의 "저장 후 기록 N건"의 기준이다. computed_at 은 배치마다 바뀌어 못 쓴다.
--   version 은 재계산마다 올린다 — recommendation.profile_version 과 짝을 이뤄 "같은 프로필이면 결과 재사용"의 키가 된다.
--   strong_store_count(저장·계획·담기·기록 가게 수)와 bread_signal_count(기록의 빵 수)는 카드 상태(CHIPS / QUIZ / 정확도 링크) 판정용.
--   tags 의 키는 store_profile 과 같은 한글 태그 이름이다.
--   게스트 → 로그인 병합 때는 PK 충돌이라 게스트 행을 버리고 재계산한다 (#663). 게스트가 회원의 16%라 빠뜨리면 안 된다.
CREATE TABLE user_taste_profile (
    member_id          BIGINT        PRIMARY KEY REFERENCES member (id),
    axis_taste         NUMERIC(4, 3),
    axis_texture       NUMERIC(4, 3),
    axis_filling       NUMERIC(4, 3),
    axis_vibe          NUMERIC(4, 3),
    type_code          VARCHAR(4),                  -- 16타입 코드. CHAR 는 Hibernate validate 가 String 과 맞춰 주지 않아 VARCHAR
    type_source        VARCHAR(10),                 -- QUIZ | SIGNAL
    quiz_answers       JSONB,                       -- [{"axis": "taste", "choice": "A"}, …] 4 또는 8개. 다시 풀면 덮어쓴다
    quiz_answered_at   TIMESTAMP,                   -- 마지막 검사 시각. 검사한 적 없으면 NULL
    tags               JSONB         NOT NULL DEFAULT '{}',   -- 가게 태그 상속 합 (0~1)
    top_kinds          TEXT[],                      -- 기록·검사에서 드러난 메뉴 종류 상위 3~5
    user_type          VARCHAR(12),                 -- PLANNER | ARCHIVER | BROWSER | COLLECTOR | DOER (90일 행동 비율 룰)
    strong_store_count INTEGER       NOT NULL DEFAULT 0,
    bread_signal_count INTEGER       NOT NULL DEFAULT 0,
    version            INTEGER       NOT NULL DEFAULT 1,
    computed_at        TIMESTAMP     NOT NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP,
    CONSTRAINT ck_user_taste_profile_type_source CHECK (type_source IS NULL OR type_source IN ('QUIZ', 'SIGNAL')),
    CONSTRAINT ck_user_taste_profile_axis_range CHECK (
        (axis_taste   IS NULL OR axis_taste   BETWEEN -1 AND 1) AND
        (axis_texture IS NULL OR axis_texture BETWEEN -1 AND 1) AND
        (axis_filling IS NULL OR axis_filling BETWEEN -1 AND 1) AND
        (axis_vibe    IS NULL OR axis_vibe    BETWEEN -1 AND 1))
);
