CREATE TABLE IF NOT EXISTS store_add_report (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT REFERENCES member (id),
    naver_place_id VARCHAR(50),
    name VARCHAR(100) NOT NULL,
    address VARCHAR(255) NOT NULL,
    road_address VARCHAR(255),
    phone VARCHAR(20),
    location GEOGRAPHY(POINT, 4326) NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_store_add_report_status_created_at
    ON store_add_report (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_store_add_report_naver_place_id
    ON store_add_report (naver_place_id)
    WHERE naver_place_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_store_add_report_pending_naver_place_id
    ON store_add_report (naver_place_id)
    WHERE naver_place_id IS NOT NULL
      AND status = 'PENDING';
