-- 필드 단위 출처 (병합 정책의 입력) — 사용자 기여 메뉴는 시딩 시 USER 소급 기록
CREATE TABLE store_field_provenance (
    store_id    BIGINT      NOT NULL,
    field       VARCHAR(60) NOT NULL,
    source      VARCHAR(20) NOT NULL,
    snapshot_id BIGINT,
    updated_at  TIMESTAMP   NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, field)
);
