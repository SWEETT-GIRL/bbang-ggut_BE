CREATE TABLE IF NOT EXISTS famous_menu_by_region (
    id          BIGSERIAL    PRIMARY KEY,
    region3rd   VARCHAR(50)  NOT NULL,
    menu_category VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS hot_store_ranking (
    id                        BIGSERIAL PRIMARY KEY,
    famous_menu_by_region_id  BIGINT    NOT NULL REFERENCES famous_menu_by_region (id),
    store_id                  BIGINT    NOT NULL REFERENCES store (id),
    rank                      INT       NOT NULL
);
