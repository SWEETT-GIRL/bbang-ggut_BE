ALTER TABLE store_contribution_report
    ADD COLUMN IF NOT EXISTS store_id BIGINT REFERENCES store (id),
    ADD COLUMN IF NOT EXISTS request_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS naver_place_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS address VARCHAR(255),
    ADD COLUMN IF NOT EXISTS road_address VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(20),
    ADD COLUMN IF NOT EXISTS location GEOGRAPHY(POINT, 4326),
    ADD COLUMN IF NOT EXISTS message TEXT,
    ADD COLUMN IF NOT EXISTS reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;

ALTER TABLE menu_contribution_report
    ADD COLUMN IF NOT EXISTS store_id BIGINT REFERENCES store (id),
    ADD COLUMN IF NOT EXISTS menu_id BIGINT REFERENCES menu (id),
    ADD COLUMN IF NOT EXISTS menu_category_id BIGINT REFERENCES menu_category (id),
    ADD COLUMN IF NOT EXISTS request_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS price INTEGER,
    ADD COLUMN IF NOT EXISTS description VARCHAR(500),
    ADD COLUMN IF NOT EXISTS message TEXT,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;

UPDATE store_contribution_report scr
SET store_id = sr.store_id,
    request_type = sr.request_type,
    message = sr.message,
    status = sr.status,
    reviewed_at = sr.reviewed_at
FROM store_report sr
WHERE scr.store_report_id = sr.id
  AND scr.store_id IS NULL;

UPDATE store_contribution_report scr
SET request_type = 'STORE_ADD',
    naver_place_id = sar.naver_place_id,
    name = sar.name,
    address = sar.address,
    road_address = sar.road_address,
    phone = sar.phone,
    location = sar.location,
    reason = sar.reason,
    status = sar.status,
    reviewed_at = sar.reviewed_at
FROM store_add_report sar
WHERE scr.store_add_report_id = sar.id
  AND scr.request_type IS NULL;

UPDATE menu_contribution_report mcr
SET store_id = mar.store_id,
    menu_category_id = mar.menu_category_id,
    request_type = 'MENU_ADD',
    name = mar.name,
    price = mar.price,
    description = mar.description,
    status = mar.status,
    reviewed_at = mar.reviewed_at
FROM menu_add_report mar
WHERE mcr.menu_add_report_id = mar.id
  AND mcr.store_id IS NULL;

UPDATE menu_contribution_report mcr
SET store_id = sr.store_id,
    menu_id = sr.menu_id,
    request_type = sr.request_type,
    message = sr.message,
    status = sr.status,
    reviewed_at = sr.reviewed_at
FROM store_report sr
WHERE mcr.store_report_id = sr.id
  AND mcr.store_id IS NULL;

UPDATE store_contribution_report scr
SET status = cr.status
FROM contribution_report cr
WHERE scr.contribution_report_id = cr.id
  AND scr.status IS NULL;

UPDATE menu_contribution_report mcr
SET status = cr.status
FROM contribution_report cr
WHERE mcr.contribution_report_id = cr.id
  AND mcr.status IS NULL;

CREATE TABLE IF NOT EXISTS store_contribution_report_image_file (
    id BIGSERIAL PRIMARY KEY,
    store_contribution_report_id BIGINT NOT NULL REFERENCES store_contribution_report (id),
    name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(50) NOT NULL,
    aws_key VARCHAR(255) NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'DONE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS menu_contribution_report_image_file (
    id BIGSERIAL PRIMARY KEY,
    menu_contribution_report_id BIGINT NOT NULL REFERENCES menu_contribution_report (id),
    name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(50) NOT NULL,
    aws_key VARCHAR(255) NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'DONE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

INSERT INTO store_contribution_report_image_file (
    store_contribution_report_id,
    name,
    mime_type,
    aws_key,
    processing_status,
    created_at,
    updated_at
)
SELECT scr.id,
       srif.name,
       srif.mime_type,
       srif.aws_key,
       srif.processing_status,
       srif.created_at,
       COALESCE(srif.updated_at, srif.created_at)
FROM store_report_image_file srif
JOIN store_contribution_report scr ON scr.store_report_id = srif.request_id
WHERE NOT EXISTS (
    SELECT 1
    FROM store_contribution_report_image_file target
    WHERE target.aws_key = srif.aws_key
);

INSERT INTO menu_contribution_report_image_file (
    menu_contribution_report_id,
    name,
    mime_type,
    aws_key,
    processing_status,
    created_at,
    updated_at
)
SELECT mcr.id,
       srif.name,
       srif.mime_type,
       srif.aws_key,
       srif.processing_status,
       srif.created_at,
       COALESCE(srif.updated_at, srif.created_at)
FROM store_report_image_file srif
JOIN menu_contribution_report mcr ON mcr.store_report_id = srif.request_id
WHERE NOT EXISTS (
    SELECT 1
    FROM menu_contribution_report_image_file target
    WHERE target.aws_key = srif.aws_key
);

INSERT INTO menu_contribution_report_image_file (
    menu_contribution_report_id,
    name,
    mime_type,
    aws_key,
    processing_status,
    created_at,
    updated_at
)
SELECT mcr.id,
       marif.name,
       marif.mime_type,
       marif.aws_key,
       marif.processing_status,
       marif.created_at,
       COALESCE(marif.updated_at, marif.created_at)
FROM menu_add_report_image_file marif
JOIN menu_contribution_report mcr ON mcr.menu_add_report_id = marif.menu_add_report_id
WHERE NOT EXISTS (
    SELECT 1
    FROM menu_contribution_report_image_file target
    WHERE target.aws_key = marif.aws_key
);

CREATE INDEX IF NOT EXISTS idx_store_contribution_report_store_status
    ON store_contribution_report (store_id, status)
    WHERE store_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_store_contribution_report_pending_naver_place_id
    ON store_contribution_report (naver_place_id)
    WHERE naver_place_id IS NOT NULL
      AND status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_menu_contribution_report_store_status
    ON menu_contribution_report (store_id, status);

CREATE INDEX IF NOT EXISTS idx_store_contribution_report_image_file_report_id
    ON store_contribution_report_image_file (store_contribution_report_id);

CREATE INDEX IF NOT EXISTS idx_store_contribution_report_image_file_aws_key
    ON store_contribution_report_image_file (aws_key);

CREATE INDEX IF NOT EXISTS idx_menu_contribution_report_image_file_report_id
    ON menu_contribution_report_image_file (menu_contribution_report_id);

CREATE INDEX IF NOT EXISTS idx_menu_contribution_report_image_file_aws_key
    ON menu_contribution_report_image_file (aws_key);

ALTER TABLE store_contribution_report
    ALTER COLUMN request_type SET NOT NULL,
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE menu_contribution_report
    ALTER COLUMN store_id SET NOT NULL,
    ALTER COLUMN request_type SET NOT NULL,
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE store_contribution_report
    DROP CONSTRAINT IF EXISTS chk_store_contribution_report_one_legacy_ref,
    DROP CONSTRAINT IF EXISTS uq_store_contribution_report_store_report,
    DROP CONSTRAINT IF EXISTS uq_store_contribution_report_store_add_report;

ALTER TABLE menu_contribution_report
    DROP CONSTRAINT IF EXISTS chk_menu_contribution_report_one_legacy_ref,
    DROP CONSTRAINT IF EXISTS uq_menu_contribution_report_menu_add_report,
    DROP CONSTRAINT IF EXISTS uq_menu_contribution_report_store_report;

DROP INDEX IF EXISTS idx_store_contribution_report_store_report_id;
DROP INDEX IF EXISTS idx_store_contribution_report_store_add_report_id;
DROP INDEX IF EXISTS idx_menu_contribution_report_menu_add_report_id;
DROP INDEX IF EXISTS idx_menu_contribution_report_store_report_id;

ALTER TABLE store_contribution_report
    DROP COLUMN IF EXISTS store_report_id,
    DROP COLUMN IF EXISTS store_add_report_id;

ALTER TABLE menu_contribution_report
    DROP COLUMN IF EXISTS menu_add_report_id,
    DROP COLUMN IF EXISTS store_report_id;

DROP TABLE IF EXISTS store_report_image_file;
DROP TABLE IF EXISTS menu_add_report_image_file;
DROP TABLE IF EXISTS store_report;
DROP TABLE IF EXISTS store_add_report;
DROP TABLE IF EXISTS menu_add_report;
