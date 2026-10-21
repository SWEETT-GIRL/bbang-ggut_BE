-- 추천 결과와 피드백, 그리고 코스·저장 가게에 붙는 컬럼 (취향 추천 설계 v2.2 §1 · §4 · §5.5, 이슈 #659). 쓰는 쪽은 #660·#661.
--
-- recommendation: 추천 요청 1건 = 행 1개. 만들어진 뒤 바꾸지 않는다(불변)라 updated_at 이 없다.
--   이 표는 서빙에 필요한 것만 갖는다 (09-08 검토, 플래닝 문서 §12).
--     재사용 키   = (course_id, mode, course_area_id 또는 anchor_store_id, day, profile_version). 같으면 저장된 result 를 다시 쓰고
--                  LLM 을 다시 부르지 않는다. profile_version 이 키에 있는 이유: 검사·배치로 프로필이 바뀌면 옛 추천은 못 쓴다.
--     result      = 사용자에게 내려간 최종본(후처리 뒤). 피드백 해석(#663 "노출 후 미담기")과 재사용의 기준.
--     provider · model · prompt_version · fallback = 피드백을 벤더·프롬프트별로 나눠 볼 최소 축.
--   설계 §4 의 여정 변수(entry_screen · session_events · user_type · day_count · days_until_start · hour_of_day)와
--   계측(latency_ms · 토큰 수), 후보 목록(candidates)은 여기 두지 않는다.
--     여정 변수 → Amplitude 이벤트 프로퍼티 (설계 §8 이 recommendation_id 와 함께 보낸다. 여정 분석은 Amplitude 가 맡는다, §3.5)
--     지연·토큰·폴백 비율·불변 조건 위반 → Micrometer (provider 태그 히스토그램·카운터)
--     후보 15곳 · LLM 원문 → recommendation_id 를 단 구조화 로그 한 줄 (운영 초기 프롬프트 디버깅용)
--   잃는 것: 로그 보존 기간이 지난 추천은 DB 만으로 "왜 이랬나"를 되짚을 수 없다. 골든셋 평가(§9.1)는 고정 픽스처 러너(#662)가
--   하므로 운영 행이 필요 없다.
--   mode = AREA(상권 추천, LLM 가능) | ANCHOR(방금 담은 가게 근처, 룰만 · provider = rule · model NULL).
--   fallback = true 면 LLM 실패·타임아웃으로 룰 결과를 내려준 것. 에러가 아니라 결과다.
CREATE TABLE recommendation (
    id              BIGSERIAL   PRIMARY KEY,
    member_id       BIGINT      NOT NULL REFERENCES member (id),
    course_id       BIGINT      NOT NULL REFERENCES courses (id),
    mode            VARCHAR(10) NOT NULL,          -- AREA | ANCHOR
    course_area_id  BIGINT      REFERENCES course_area (id),
    anchor_store_id BIGINT,                        -- ANCHOR 일 때 방금 담은 가게. 로그성이라 FK 없음
    day             SMALLINT,                      -- 요청의 day (재사용 키)
    profile_version INTEGER     NOT NULL,          -- user_taste_profile.version 스냅샷 (재사용 키)
    provider        VARCHAR(20) NOT NULL,          -- rule | gemini | openai | anthropic
    model           VARCHAR(60),
    prompt_version  VARCHAR(20),                   -- 설계 §7.1 프롬프트 규칙 버전. 룰만 돌았으면 NULL
    fallback        BOOLEAN     NOT NULL DEFAULT FALSE,
    result          JSONB       NOT NULL,          -- {title, stores: [{storeId, order, reason, tasteFit, distanceToNextM}], totalDistanceM}
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_recommendation_mode CHECK (mode IN ('AREA', 'ANCHOR'))
);

-- 재사용 조회: 같은 코스·모드·상권/앵커·날·프로필 버전의 최신 한 건.
CREATE INDEX idx_recommendation_reuse
    ON recommendation (course_id, mode, course_area_id, anchor_store_id, day, profile_version, created_at DESC);

-- 피드백 가중치 계산(#663)은 "한 사람의 추천 이력"을 본다.
CREATE INDEX idx_recommendation_member_created
    ON recommendation (member_id, created_at DESC);

-- recommendation_feedback: 추천 결과에 사용자가 보인 반응. 노출 후 미담기(-0.3)·체크 해제(-1) 같은 음의 신호의 출처(§3.1).
--   서버 표에 흔적이 없는 행동이라 따로 받는다. store_id 는 가게 단위 반응(STORE_TAP · STORE_UNCHECK)에만 있고,
--   VIEW · SAVE · DISMISS 는 추천 전체에 대한 반응이라 NULL. 한 번 쓰면 바꾸지 않는다.
CREATE TABLE recommendation_feedback (
    id                BIGSERIAL   PRIMARY KEY,
    recommendation_id BIGINT      NOT NULL REFERENCES recommendation (id),
    store_id          BIGINT,
    action            VARCHAR(20) NOT NULL,        -- VIEW | STORE_TAP | STORE_UNCHECK | SAVE | DISMISS
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_recommendation_feedback_action
        CHECK (action IN ('VIEW', 'STORE_TAP', 'STORE_UNCHECK', 'SAVE', 'DISMISS'))
);

CREATE INDEX idx_recommendation_feedback_recommendation
    ON recommendation_feedback (recommendation_id);

-- courses: 추천으로 채워진 코스임을 남긴다. bulk 담기(§1 "담기")가 source = RECOMMENDED 와 recommendation_id 를 쓴다.
-- 이후 여정(course_start · course_store_visit · record_create_success)에 recommendation_id 가 따라간다(§8).
-- source 가 NULL 이면 직접 만든 코스. 값 체계는 앱의 CourseSource enum 이 정하므로 CHECK 는 두지 않는다.
ALTER TABLE courses
    ADD COLUMN source            VARCHAR(16),
    ADD COLUMN recommendation_id BIGINT REFERENCES recommendation (id);

CREATE INDEX idx_courses_recommendation
    ON courses (recommendation_id)
    WHERE recommendation_id IS NOT NULL;

-- course_stores: 가게마다 어느 추천에서 왔는지. 한 코스에 상권 추천으로 담은 4곳, 앵커 추천으로 담은 1곳, 직접 담은 2곳이
-- 섞이므로 코스 단위(courses.recommendation_id)만으로는 §9.2 의 "추천 vs 앵커 vs 직접" 비교가 안 된다. 직접 담았으면 NULL.
ALTER TABLE course_stores
    ADD COLUMN recommendation_id BIGINT REFERENCES recommendation (id);

CREATE INDEX idx_course_stores_recommendation
    ON course_stores (recommendation_id)
    WHERE recommendation_id IS NOT NULL;

-- saved_stores.deleted_at 은 설계 §4 에 있었지만 두지 않는다 (09-08). (member_id, store_id) 유니크와 충돌하고,
-- "저장 해제 -1" 신호는 해제 API 시점에 user_store_interaction 에 UNSAVE 행을 넣으면 잡힌다. 과거분은 Amplitude 백필.
