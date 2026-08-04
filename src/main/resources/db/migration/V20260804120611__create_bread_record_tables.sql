CREATE TABLE bread_record (
    id          BIGSERIAL    PRIMARY KEY,
    member_id   BIGINT       NOT NULL REFERENCES member (id),
    store_id    BIGINT       REFERENCES store (id),
    store_name  VARCHAR(100) NOT NULL,
    region_key  VARCHAR(100),
    memo        VARCHAR(500),
    loved_bread VARCHAR(100),
    eaten_at    TIMESTAMP    NOT NULL,
    deleted_at  TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP
);

CREATE INDEX idx_bread_record_member_eaten_at
    ON bread_record (member_id, eaten_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_bread_record_member_store
    ON bread_record (member_id, store_id)
    WHERE deleted_at IS NULL AND store_id IS NOT NULL;

CREATE TABLE bread_record_photo (
    id                  BIGSERIAL    PRIMARY KEY,
    record_id           BIGINT       NOT NULL REFERENCES bread_record (id),
    name                VARCHAR(255) NOT NULL,
    mime_type           VARCHAR(50)  NOT NULL,
    aws_key             VARCHAR(255) NOT NULL,
    processing_status   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    resized_key_320     VARCHAR(255),
    resized_key_640     VARCHAR(255),
    resized_key_1024    VARCHAR(255),
    sort_order          INTEGER      NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP,
    CONSTRAINT uq_bread_record_photo_order UNIQUE (record_id, sort_order)
);

CREATE INDEX idx_bread_record_photo_record_status
    ON bread_record_photo (record_id, processing_status);

CREATE TABLE bread_record_tag (
    id              BIGSERIAL        PRIMARY KEY,
    record_id       BIGINT           NOT NULL REFERENCES bread_record (id),
    photo_id        BIGINT           NOT NULL REFERENCES bread_record_photo (id),
    name            VARCHAR(100)     NOT NULL,
    bread_category  VARCHAR(50)      NOT NULL,
    x               DOUBLE PRECISION NOT NULL,
    y               DOUBLE PRECISION NOT NULL,
    created_at      TIMESTAMP        NOT NULL,
    updated_at      TIMESTAMP,
    CONSTRAINT ck_bread_record_tag_x CHECK (x >= 0 AND x <= 1),
    CONSTRAINT ck_bread_record_tag_y CHECK (y >= 0 AND y <= 1)
);

CREATE INDEX idx_bread_record_tag_record
    ON bread_record_tag (record_id);

CREATE INDEX idx_bread_record_tag_photo
    ON bread_record_tag (photo_id);
