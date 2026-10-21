-- 코스
CREATE TABLE IF NOT EXISTS courses (
    id          BIGSERIAL       PRIMARY KEY,
    type        VARCHAR(20)     NOT NULL,
    title       VARCHAR(100)    NOT NULL,
    member_id   BIGINT          REFERENCES member (id),
    is_public   BOOLEAN         NOT NULL,
    deleted_at  TIMESTAMP,
    created_at  TIMESTAMP       NOT NULL,
    updated_at  TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_courses_member_id
    ON courses (member_id)
    WHERE member_id IS NOT NULL;

-- 코스 구성 빵집 (방문 순서)
CREATE TABLE IF NOT EXISTS course_stops (
    id          BIGSERIAL   PRIMARY KEY,
    course_id   BIGINT      NOT NULL REFERENCES courses (id),
    store_id    BIGINT      NOT NULL REFERENCES store (id),
    visit_order INTEGER     NOT NULL,
    memo        TEXT,
    deleted_at  TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uq_course_stop_order UNIQUE (course_id, visit_order),
    CONSTRAINT uq_course_stop_store UNIQUE (course_id, store_id)
);

CREATE INDEX IF NOT EXISTS idx_course_stops_course_id
    ON course_stops (course_id);

-- 코스 대표 이미지 (ImageFile 상속)
CREATE TABLE IF NOT EXISTS course_image_file (
    id                  BIGSERIAL       PRIMARY KEY,
    name                VARCHAR(255)    NOT NULL,
    mime_type           VARCHAR(50)     NOT NULL,
    aws_key             VARCHAR(255)    NOT NULL,
    processing_status   VARCHAR(20)     NOT NULL DEFAULT 'DONE',
    course_id           BIGINT          NOT NULL REFERENCES courses (id),
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP
);

-- 저장한 코스 (CUSTOM + CURATED 통합 보관함)
CREATE TABLE IF NOT EXISTS saved_courses (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL REFERENCES member (id),
    course_id   BIGINT      NOT NULL REFERENCES courses (id),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uq_saved_course UNIQUE (member_id, course_id)
);

CREATE INDEX IF NOT EXISTS idx_saved_courses_member_id
    ON saved_courses (member_id);

-- 코스 좋아요
CREATE TABLE IF NOT EXISTS course_like (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL REFERENCES member (id),
    course_id   BIGINT      NOT NULL REFERENCES courses (id),
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uq_course_like UNIQUE (member_id, course_id)
);

CREATE INDEX IF NOT EXISTS idx_course_like_course_id
    ON course_like (course_id);

-- 찜한 가게
CREATE TABLE IF NOT EXISTS wished_stores (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL REFERENCES member (id),
    store_id    BIGINT      NOT NULL REFERENCES store (id),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uq_wished_store UNIQUE (member_id, store_id)
);

CREATE INDEX IF NOT EXISTS idx_wished_stores_member_id
    ON wished_stores (member_id);
