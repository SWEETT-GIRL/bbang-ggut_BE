ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS source_course_id BIGINT REFERENCES courses(id);
