CREATE TABLE store_plan (
    member_id BIGINT       NOT NULL REFERENCES member (id),
    store_id  BIGINT       NOT NULL REFERENCES store (id),
    memo      VARCHAR(200) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP,
    PRIMARY KEY (member_id, store_id)
);

CREATE INDEX idx_store_plan_store
    ON store_plan (store_id);
