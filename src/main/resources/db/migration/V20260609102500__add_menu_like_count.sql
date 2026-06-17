ALTER TABLE menu
	ADD COLUMN IF NOT EXISTS like_count INTEGER;

UPDATE menu
SET like_count = 0
WHERE like_count IS NULL;

ALTER TABLE menu
	ALTER COLUMN like_count SET DEFAULT 0;

DO
$$
BEGIN
	IF EXISTS (SELECT 1
			   FROM information_schema.columns
			   WHERE table_schema = current_schema()
				 AND table_name = 'menu'
				 AND column_name = 'like_count'
				 AND is_nullable = 'YES') THEN
		ALTER TABLE menu
			ALTER COLUMN like_count SET NOT NULL;
	END IF;
END
$$;
