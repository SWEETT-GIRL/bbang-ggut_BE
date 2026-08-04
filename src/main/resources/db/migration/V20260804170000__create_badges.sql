CREATE TABLE badges (
    id              BIGSERIAL       PRIMARY KEY,
    code            VARCHAR(50)     NOT NULL UNIQUE,
    image_key       VARCHAR(255),
    title           VARCHAR(100)    NOT NULL,
    hint            VARCHAR(255)    NOT NULL,
    condition_type  VARCHAR(30)     NOT NULL,
    threshold       INTEGER,
    sort_order      INTEGER         NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL,
    updated_at      TIMESTAMP,
    CONSTRAINT chk_badge_condition_type CHECK (
        condition_type IN (
            'STARTER_ALL',
            'SAVED_STORE_COUNT',
            'COURSE_COUNT',
            'RECORD_COUNT',
            'TOUR_FINISH_COUNT',
            'STORE_REVISIT'
        )
    ),
    CONSTRAINT chk_badge_threshold CHECK (
        (condition_type = 'STARTER_ALL' AND threshold IS NULL)
        OR (condition_type <> 'STARTER_ALL' AND threshold > 0)
    )
);

CREATE INDEX idx_badges_active_sort
    ON badges (sort_order, id)
    WHERE active = TRUE;

CREATE TABLE member_badges (
    member_id      BIGINT       NOT NULL REFERENCES member (id),
    badge_id       BIGINT       NOT NULL REFERENCES badges (id),
    achieved_at    TIMESTAMP    NOT NULL,
    celebrated_at  TIMESTAMP,
    PRIMARY KEY (member_id, badge_id)
);

CREATE INDEX idx_member_badges_member_achieved
    ON member_badges (member_id, achieved_at DESC);

INSERT INTO badges (
    code, image_key, title, hint, condition_type, threshold, sort_order, active, created_at
) VALUES
    ('welcome', NULL, '웰컴 빵순이', '시작 미션 3개 완료', 'STARTER_ALL', NULL, 1, TRUE, CURRENT_TIMESTAMP),
    ('first-finish', NULL, '첫 완주', '빵투어 하나를 끝까지', 'TOUR_FINISH_COUNT', 1, 2, TRUE, CURRENT_TIMESTAMP),
    ('regular', NULL, '단골 탄생', '같은 빵집 기록 3번', 'STORE_REVISIT', 3, 3, TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;
