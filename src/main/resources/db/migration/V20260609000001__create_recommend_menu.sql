CREATE TABLE IF NOT EXISTS recommend_menu (
    id         BIGSERIAL PRIMARY KEY,
    member_id  BIGINT    NOT NULL REFERENCES member (id),
    menu_id    BIGINT    NOT NULL REFERENCES menu (id),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_recommend_menu UNIQUE (member_id, menu_id)
);
