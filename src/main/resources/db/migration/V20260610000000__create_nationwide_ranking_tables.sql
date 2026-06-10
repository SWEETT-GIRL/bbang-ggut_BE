CREATE TABLE IF NOT EXISTS nationwide_famous_menu (
    id              BIGSERIAL    PRIMARY KEY,
    menu_category   VARCHAR(50)  NOT NULL
);

CREATE TABLE IF NOT EXISTS nationwide_hot_store_ranking (
    id                          BIGSERIAL PRIMARY KEY,
    nationwide_famous_menu_id   BIGINT    NOT NULL REFERENCES nationwide_famous_menu (id),
    store_id                    BIGINT    NOT NULL REFERENCES store (id),
    rank                        INT       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_nationwide_famous_menu_category
    ON nationwide_famous_menu (menu_category);

CREATE INDEX IF NOT EXISTS idx_nationwide_hot_store_menu_rank
    ON nationwide_hot_store_ranking (nationwide_famous_menu_id, rank);

CREATE INDEX IF NOT EXISTS idx_nationwide_hot_store_store
    ON nationwide_hot_store_ranking (store_id);
