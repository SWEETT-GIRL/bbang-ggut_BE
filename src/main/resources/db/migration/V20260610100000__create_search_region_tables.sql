CREATE TABLE IF NOT EXISTS search_region (
    id                  BIGSERIAL    PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    type                VARCHAR(30)  NOT NULL,
    level               VARCHAR(30)  NOT NULL,
    parent_region_id    BIGINT       REFERENCES search_region (id),
    center_lat          DOUBLE PRECISION,
    center_lng          DOUBLE PRECISION,
    radius_meters       INTEGER,
    display_order       INTEGER      NOT NULL DEFAULT 0,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP,
    CONSTRAINT ck_search_region_type
        CHECK (type IN ('DONG', 'STATION', 'HOT_PLACE', 'LANDMARK', 'DISTRICT')),
    CONSTRAINT ck_search_region_level
        CHECK (level IN ('CITY', 'GU', 'DONG', 'AREA')),
    CONSTRAINT ck_search_region_radius_meters
        CHECK (radius_meters IS NULL OR radius_meters >= 0)
);

CREATE INDEX IF NOT EXISTS idx_search_region_type_active
    ON search_region (type, is_active);

CREATE INDEX IF NOT EXISTS idx_search_region_level_active
    ON search_region (level, is_active);

CREATE INDEX IF NOT EXISTS idx_search_region_parent_region_id
    ON search_region (parent_region_id);

CREATE TABLE IF NOT EXISTS search_region_alias (
    id                  BIGSERIAL    PRIMARY KEY,
    region_id           BIGINT       NOT NULL REFERENCES search_region (id),
    alias               VARCHAR(100) NOT NULL,
    normalized_alias    VARCHAR(100) NOT NULL,
    is_primary          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_search_region_alias_normalized_alias
        UNIQUE (normalized_alias),
    CONSTRAINT uq_search_region_alias_region_alias
        UNIQUE (region_id, alias)
);

CREATE INDEX IF NOT EXISTS idx_search_region_alias_region_id
    ON search_region_alias (region_id);

CREATE TABLE IF NOT EXISTS search_region_relation (
    id                  BIGSERIAL   PRIMARY KEY,
    from_region_id      BIGINT      NOT NULL REFERENCES search_region (id),
    to_region_id        BIGINT      NOT NULL REFERENCES search_region (id),
    relation_type       VARCHAR(30) NOT NULL,
    weight              NUMERIC(4, 2) NOT NULL DEFAULT 1.00,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP,
    CONSTRAINT ck_search_region_relation_type
        CHECK (relation_type IN ('CONTAINS', 'NEARBY', 'REPRESENTS')),
    CONSTRAINT ck_search_region_relation_weight
        CHECK (weight >= 0),
    CONSTRAINT ck_search_region_relation_not_self
        CHECK (from_region_id <> to_region_id),
    CONSTRAINT uq_search_region_relation
        UNIQUE (from_region_id, to_region_id, relation_type)
);

CREATE INDEX IF NOT EXISTS idx_search_region_relation_from_region_id
    ON search_region_relation (from_region_id);

CREATE INDEX IF NOT EXISTS idx_search_region_relation_to_region_id
    ON search_region_relation (to_region_id);

CREATE TABLE IF NOT EXISTS store_search_region (
    id                  BIGSERIAL   PRIMARY KEY,
    store_id            BIGINT      NOT NULL REFERENCES store (id),
    region_id           BIGINT      NOT NULL REFERENCES search_region (id),
    weight              NUMERIC(4, 2) NOT NULL DEFAULT 1.00,
    mapping_type        VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    is_primary          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP,
    CONSTRAINT ck_store_search_region_mapping_type
        CHECK (mapping_type IN ('MANUAL', 'AUTO', 'OVERRIDE')),
    CONSTRAINT ck_store_search_region_weight
        CHECK (weight >= 0),
    CONSTRAINT uq_store_search_region
        UNIQUE (store_id, region_id)
);

CREATE INDEX IF NOT EXISTS idx_store_search_region_store_id
    ON store_search_region (store_id);

CREATE INDEX IF NOT EXISTS idx_store_search_region_region_id
    ON store_search_region (region_id);
