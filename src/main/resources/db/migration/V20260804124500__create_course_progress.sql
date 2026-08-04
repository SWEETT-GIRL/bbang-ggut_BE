CREATE TABLE course_progress (
    member_id      BIGINT      NOT NULL REFERENCES member (id),
    course_id      BIGINT      NOT NULL REFERENCES courses (id),
    started_at     TIMESTAMP   NOT NULL,
    completed_at   TIMESTAMP,
    stopped_at     TIMESTAMP,
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP,
    PRIMARY KEY (member_id, course_id)
);

CREATE INDEX idx_course_progress_active_member
    ON course_progress (member_id, started_at DESC)
    WHERE stopped_at IS NULL AND completed_at IS NULL;

CREATE INDEX idx_course_progress_completed_course
    ON course_progress (course_id)
    WHERE completed_at IS NOT NULL;
