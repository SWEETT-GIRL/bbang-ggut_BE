-- 코스용 상권 = 빵여권 도장 칸 (취향 추천 설계 v2.2 §1 · §4, 이슈 #659). 시드는 dataops 초안이 오면 별도로 넣는다.
--
-- 왜 테이블인가: 코스 생성 직후 화면에는 위치가 없다(사용자가 고른 건 시/도와 날짜뿐). 서버가 "이 시/도의 빵 동네" 후보를
-- 골라 칩으로 주고, 고른 동네의 중심·반경으로 후보 15곳을 뽑아야 하므로 서버 쪽에 도장 칸 목록 + 좌표·반경·동선 기준이
-- 있어야 한다. FE 의 BREAD_SPOTS(도장판)는 좌표가 없는 상수라 그대로 못 쓴다. 이 표가 도장판의 서버 판이고, 뒤에 FE 도
-- 이 표를 받아 쓰면 출처가 하나가 된다. 앵커 추천(가게 담은 직후)은 앵커 좌표 + 반경으로 끝나므로 이 표를 쓰지 않는다.
--
-- search_region 을 그대로 쓰지 않는 이유: DONG·DISTRICT 행은 center·radius 가 NULL 이고(좌표는 HOT_PLACE·STATION·LANDMARK
-- 만), 연남·서교·망원 병합 같은 상권을 표현할 수 없다. HOT_PLACE 행이 있으면 search_region_id 로 참조만 한다 (성수 = id 5).
--
-- 소속 판정 = 반경 안 OR 지역 일치. 반경이 주 규칙이다 — store.region_3rd 어휘가 행정동식 이름·N가 제거·도로명까지 섞여
-- 있어 텍스트 매칭은 보조로만 쓴다. 설계 v2.2 은 동 목록 테이블(course_area_dong)을 따로 뒀지만 09-07 검토에서
-- 상권 행의 선택 컬럼으로 줄였다: 서울 밖 도장 칸은 시군구 단위(수원시, 강릉시, 인천 중구 …)라 동 목록으로 표현되지 않는다.
--   region_2nd 만 있음  → 그 시군구 전체
--   region_2nd + 3rd    → 그 읍면동 (서울 도장 칸: 성동구 성수동 …). 값은 store.region_3rd 에 실제로 있는 표기로 넣는다
--   둘 다 NULL          → 반경만
-- scale: WALK 는 도보 상권(취향 후보가 부족해도 반경을 넓히지 않음), CAR 는 차량 상권(반경 1.5배씩 2번 확장).
-- max_hop_m: 코스 안 인접 두 가게 사이의 상한(불변 조건, 설계 §5.5).
-- sido 는 courses.region 과 같은 표기('서울'), name 은 도장 칸 라벨과 같은 표기('성수동')라 FE 가 칩과 도장을 같은
-- 문자열로 맞출 수 있다.
CREATE TABLE course_area (
    id               BIGSERIAL        PRIMARY KEY,
    sido             VARCHAR(20)      NOT NULL,     -- courses.region 과 같은 표기
    name             VARCHAR(50)      NOT NULL,     -- 도장 칸 라벨 (예: 성수동)
    search_region_id BIGINT           REFERENCES search_region (id),
    center_lat       DOUBLE PRECISION NOT NULL,     -- search_region.center_lat 와 같은 타입
    center_lng       DOUBLE PRECISION NOT NULL,
    scale            VARCHAR(10)      NOT NULL,     -- WALK | CAR
    radius_m         INTEGER          NOT NULL,
    max_hop_m        INTEGER          NOT NULL,
    region_2nd       VARCHAR(50),                   -- 소속 판정 보조: 시군구 (store.region_2nd 표기)
    region_3rd       VARCHAR(50),                   -- 소속 판정 보조: 읍면동 (store.region_3rd 표기). region_2nd 가 있어야 한다
    is_active        BOOLEAN          NOT NULL DEFAULT TRUE,
    display_order    INTEGER          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP        NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP,
    CONSTRAINT ck_course_area_scale  CHECK (scale IN ('WALK', 'CAR')),
    CONSTRAINT ck_course_area_radius CHECK (radius_m > 0 AND max_hop_m > 0),
    CONSTRAINT ck_course_area_region CHECK (region_3rd IS NULL OR region_2nd IS NOT NULL),
    CONSTRAINT uq_course_area_sido_name UNIQUE (sido, name)
);

-- 추천 카드는 "이 시/도의 활성 상권"만 표시 순서대로 읽는다.
CREATE INDEX idx_course_area_sido_active
    ON course_area (sido, display_order)
    WHERE is_active;

-- 역방향: 가게의 시군구·동으로 상권을 찾는다 (앵커 추천에서 앵커가 속한 상권의 max_hop_m 을 볼 때).
CREATE INDEX idx_course_area_region
    ON course_area (region_2nd, region_3rd)
    WHERE region_2nd IS NOT NULL;

-- dataops 가 상권 시드 초안을 대조할 때 읽는다. 롤이 없는 환경(로컬·CI·랩)에서는 조용히 건너뛴다.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_reader') THEN
        GRANT SELECT ON course_area TO dataops_reader;
    END IF;
END $$;
