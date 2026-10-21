-- 공공데이터 신규 인허가 후보 (4단계에서 활성화) — status 자체가 워크리스트, 폐업은 change_proposal 로
CREATE TABLE new_store_candidate (
    id                  BIGSERIAL PRIMARY KEY,
    mgmt_no             VARCHAR(60)  NOT NULL UNIQUE,
    name                VARCHAR(100) NOT NULL,
    road_address        VARCHAR(200),
    jibun_address       VARCHAR(200),
    category_code       VARCHAR(40),
    licensed_at         DATE,
    status              VARCHAR(20) NOT NULL DEFAULT 'WAITING_NAVER',
    naver_place_id      VARCHAR(50),
    match_attempts      INT NOT NULL DEFAULT 0,
    registered_store_id BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_candidate_status ON new_store_candidate (status);
