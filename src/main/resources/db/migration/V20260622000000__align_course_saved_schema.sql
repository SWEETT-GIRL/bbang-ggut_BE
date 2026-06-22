ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS like_count INTEGER DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS badge VARCHAR(30);

ALTER TABLE course_image_files
    ADD COLUMN IF NOT EXISTS display_order INTEGER DEFAULT 0 NOT NULL;

DO $$
BEGIN
    IF to_regclass('public.course_stores') IS NULL
        AND to_regclass('public.course_stops') IS NOT NULL THEN
        ALTER TABLE course_stops RENAME TO course_stores;
    END IF;
END $$;

ALTER TABLE course_stores
    ADD COLUMN IF NOT EXISTS visit_time TIME;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_course_stop_order'
    ) THEN
        ALTER TABLE course_stores
            RENAME CONSTRAINT uq_course_stop_order TO uq_course_store_order;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_course_stop_store'
    ) THEN
        ALTER TABLE course_stores
            RENAME CONSTRAINT uq_course_stop_store TO uq_course_store_store;
    END IF;
END $$;

ALTER INDEX IF EXISTS idx_course_stops_course_id RENAME TO idx_course_stores_course_id;

CREATE INDEX IF NOT EXISTS idx_course_stores_course_id
    ON course_stores (course_id);

DO $$
BEGIN
    IF to_regclass('public.saved_stores') IS NULL
        AND to_regclass('public.wished_stores') IS NOT NULL THEN
        ALTER TABLE wished_stores RENAME TO saved_stores;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_wished_store'
    ) THEN
        ALTER TABLE saved_stores
            RENAME CONSTRAINT uq_wished_store TO uq_saved_store;
    END IF;
END $$;

ALTER INDEX IF EXISTS idx_wished_stores_member_id RENAME TO idx_saved_stores_member_id;

CREATE INDEX IF NOT EXISTS idx_saved_stores_member_id
    ON saved_stores (member_id);

CREATE INDEX IF NOT EXISTS idx_courses_public_recent
    ON courses (created_at DESC)
    WHERE is_public = TRUE AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_course_likes_member_id
    ON course_likes (member_id);

CREATE INDEX IF NOT EXISTS idx_saved_courses_course_id
    ON saved_courses (course_id);

CREATE INDEX IF NOT EXISTS idx_saved_stores_store_id
    ON saved_stores (store_id);
