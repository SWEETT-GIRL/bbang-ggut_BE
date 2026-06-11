ALTER TABLE menu
    ADD COLUMN IF NOT EXISTS like_count INTEGER;

UPDATE menu
SET like_count = 0
WHERE like_count IS NULL;

ALTER TABLE menu
    ALTER COLUMN like_count SET DEFAULT 0,
    ALTER COLUMN like_count SET NOT NULL;
