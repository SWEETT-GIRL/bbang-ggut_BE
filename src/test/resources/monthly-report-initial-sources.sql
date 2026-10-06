CREATE TABLE request_log (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id bigint, http_method varchar(10), endpoint varchar(500),
    response_status integer, created_at timestamp NOT NULL
);
CREATE TABLE user_store_interaction (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id bigint, store_id bigint, type varchar(24), occurred_at timestamp NOT NULL
);
