-- 적재가 성공한 날짜만 같은 트랜잭션에서 기록한다. 기존 성공 여부는 추측해 채우지 않는다.
CREATE TABLE user_interaction_ingest_day (
    ingest_date date PRIMARY KEY,
    completed_at timestamptz NOT NULL
);
