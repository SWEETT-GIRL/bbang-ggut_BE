ALTER TABLE menu
    ALTER COLUMN menu_category_id DROP NOT NULL,
    ADD COLUMN created_by_member_id BIGINT REFERENCES member (id),
    ADD COLUMN normalized_name VARCHAR(100);

UPDATE menu
SET normalized_name = LOWER(REGEXP_REPLACE(name, '[[:space:]]+', '', 'g'));

ALTER TABLE menu
    ALTER COLUMN normalized_name SET NOT NULL;

CREATE INDEX idx_menu_store_normalized_name_active
    ON menu (store_id, normalized_name)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_menu_created_by_member
    ON menu (created_by_member_id)
    WHERE created_by_member_id IS NOT NULL;
