-- 법정동 해소 근거 (v3 P4-4 · FR-11) — 좌표 역산 결과를 버전 있는 기준표로 검증한 불변 기록.
--
-- 이전에는 좌표 역산 결과를 scratch.region_kakao 에 덮어써 쌓았고, "해소"는 운영 search_region 에
-- (구, 동) 이름쌍이 있는지만 봤다. 동명 지역에서 틀릴 수 있고, 근거가 어느 기준표·어느 좌표에서
-- 나왔는지도 남지 않았다. 여기서는 한 번의 역산을 한 행으로 남기고 고치지 않는다(append-only).
--
-- 규율
-- - 법정동 코드(bjd_code)는 B 타입일 때만 채운다. 행정동(H) 코드는 h_code 에 따로 둔다 — H 를 법정동으로 쓰지 않는다
-- - RESOLVED 는 B 타입 + 기준표 대조 통과 + 앱 3필드 투영이 선 경우만이다
-- - 조회 좌표(location_fingerprint)가 다르면 다른 근거다. 캐시는 가게 ID 가 아니라 좌표·기준표·투영 버전으로 찾는다
CREATE TABLE IF NOT EXISTS region_resolution (
    id                   BIGSERIAL PRIMARY KEY,
    subject_type         VARCHAR(20)      NOT NULL,   -- STORE / CANDIDATE
    subject_id           VARCHAR(60)      NOT NULL,   -- store_id 또는 mgmt_no
    purpose              VARCHAR(40)      NOT NULL,   -- ADDRESS_PROPOSAL / REGION_PROPOSAL / REGISTRATION / AUDIT
    source               VARCHAR(30)      NOT NULL,   -- KAKAO_COORD2REGION
    query_lon            DOUBLE PRECISION NOT NULL,
    query_lat            DOUBLE PRECISION NOT NULL,
    location_fingerprint VARCHAR(40)      NOT NULL,   -- 소수 6자리 "lon,lat"
    region_type          VARCHAR(10)      NOT NULL,   -- B / H / UNKNOWN
    status               VARCHAR(20)      NOT NULL,   -- RESOLVED / UNRESOLVED
    reason               VARCHAR(40),
    bjd_code             VARCHAR(10),
    h_code               VARCHAR(10),
    hierarchy            JSONB,
    raw_response         JSONB            NOT NULL,
    reference_version    VARCHAR(40)      NOT NULL,
    projection_version   VARCHAR(40)      NOT NULL,
    region_1st           VARCHAR(50),
    region_2nd           VARCHAR(50),
    region_3rd           VARCHAR(50),
    be_region3rd_key     VARCHAR(80),                 -- BE Region3rd 로 풀리는지(소비자 준비도) — 해소 판정과 무관
    observed_at          TIMESTAMP        NOT NULL,
    created_at           TIMESTAMP        NOT NULL DEFAULT now(),
    CONSTRAINT region_resolution_bjd_only_for_b CHECK (region_type = 'B' OR bjd_code IS NULL),
    CONSTRAINT region_resolution_bjd_format CHECK (bjd_code IS NULL OR bjd_code ~ '^[0-9]{10}$'),
    CONSTRAINT region_resolution_resolved_complete CHECK (
        status <> 'RESOLVED'
        OR (region_type = 'B' AND bjd_code IS NOT NULL AND region_1st IS NOT NULL AND region_3rd IS NOT NULL)),
    CONSTRAINT region_resolution_status CHECK (status IN ('RESOLVED', 'UNRESOLVED')),
    CONSTRAINT region_resolution_type CHECK (region_type IN ('B', 'H', 'UNKNOWN'))
);

-- 승인 게이트가 "이 가게, 이 좌표, 지금 버전"의 최신 근거를 찾는 길
CREATE INDEX IF NOT EXISTS idx_region_resolution_lookup
    ON region_resolution (subject_type, subject_id, location_fingerprint, reference_version, projection_version, created_at DESC);
