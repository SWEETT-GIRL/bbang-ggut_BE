ALTER TABLE course_progress
    ADD COLUMN status VARCHAR(20),
    ADD COLUMN finished_at TIMESTAMP;

UPDATE course_progress
SET status = CASE
        WHEN completed_at IS NOT NULL THEN 'FINISHED'
        WHEN stopped_at IS NOT NULL THEN 'STOPPED'
        ELSE 'IN_PROGRESS'
    END,
    finished_at = completed_at;

ALTER TABLE course_progress
	ALTER COLUMN status SET NOT NULL,
	ALTER COLUMN status SET DEFAULT 'IN_PROGRESS',
	ADD CONSTRAINT chk_course_progress_status
        CHECK (status IN ('IN_PROGRESS', 'FINISHED', 'STOPPED'));

DROP INDEX IF EXISTS idx_course_progress_active_member;
DROP INDEX IF EXISTS idx_course_progress_completed_course;

CREATE INDEX idx_course_progress_active_member
    ON course_progress (member_id, started_at DESC)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_course_progress_finished_course
	ON course_progress (course_id)
	WHERE status = 'FINISHED';
