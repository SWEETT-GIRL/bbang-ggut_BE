CREATE TABLE missions (
    id                BIGSERIAL       PRIMARY KEY,
    code              VARCHAR(50)     NOT NULL UNIQUE,
    title             VARCHAR(100)    NOT NULL,
    condition_type    VARCHAR(30)     NOT NULL,
    threshold         INTEGER,
    action_deeplink   VARCHAR(255)    NOT NULL,
    badge_code        VARCHAR(50)     REFERENCES badges (code),
    sort_order        INTEGER         NOT NULL,
    active            BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP       NOT NULL,
    updated_at        TIMESTAMP,
    CONSTRAINT chk_mission_condition_type CHECK (
        condition_type IN (
            'STARTER_ALL',
            'SAVED_STORE_COUNT',
            'COURSE_COUNT',
            'RECORD_COUNT',
            'TOUR_FINISH_COUNT',
            'STORE_REVISIT'
        )
    ),
    CONSTRAINT chk_mission_threshold CHECK (
        (condition_type = 'STARTER_ALL' AND threshold IS NULL)
        OR (condition_type <> 'STARTER_ALL' AND threshold > 0)
    )
);

CREATE INDEX idx_missions_active_sort
    ON missions (sort_order, id)
    WHERE active = TRUE;

INSERT INTO missions (
    code, title, condition_type, threshold, action_deeplink, badge_code,
    sort_order, active, created_at
) VALUES
    ('starter-save', '가고 싶은 빵집 찜하기', 'SAVED_STORE_COUNT', 1,
        'daedongbbangmap://search', 'welcome', 1, TRUE, CURRENT_TIMESTAMP),
    ('starter-record', '첫 빵 기록 남기기', 'RECORD_COUNT', 1,
        'daedongbbangmap://bbanglog/record', 'welcome', 2, TRUE, CURRENT_TIMESTAMP),
    ('starter-tour', '첫 빵투어 만들기', 'COURSE_COUNT', 1,
        'daedongbbangmap://my-courses', 'welcome', 3, TRUE, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;
