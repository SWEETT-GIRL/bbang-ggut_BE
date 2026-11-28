-- Original local snapshot/log IDs belong to a different database; retain them as evidence, not foreign keys.
CREATE TABLE menu_provenance_restoration (
    store_id BIGINT NOT NULL,
    field VARCHAR(60) NOT NULL,
    bundle_sha256 VARCHAR(64) NOT NULL,
    evidence JSONB NOT NULL,
    restored_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, field),
    CHECK (field ~ '^menu\.[0-9]+\.description$')
);
