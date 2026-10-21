-- 보상 수치. 배포 없이 어드민에서 조정한다(앱의 fallback 은 src/constants/rewards.ts).
-- 값이 전부 정수라 키 하나에 정수 하나다. 배열은 키를 나눈다(attendance.day1 … day7).
CREATE TABLE reward_settings (
    setting_key VARCHAR(50) PRIMARY KEY,
    int_value   INTEGER     NOT NULL,
    updated_at  TIMESTAMP
);

INSERT INTO reward_settings (setting_key, int_value) VALUES
    ('welcome', 25),
    ('record', 10),
    ('record.photo', 5),
    ('record.daily-cap', 3),
    ('attendance.day1', 5),
    ('attendance.day2', 5),
    ('attendance.day3', 10),
    ('attendance.day4', 5),
    ('attendance.day5', 10),
    ('attendance.day6', 10),
    ('attendance.day7', 30),
    -- 룰렛 칸. 시계 방향, 12 시가 wedge0 — 앱의 룰렛 그림 순서와 같아야 한다
    ('feed.wedge0', 10),
    ('feed.wedge1', 5),
    ('feed.wedge2', 3),
    ('feed.wedge3', 5),
    ('feed.wedge4', 10),
    ('feed.wedge5', 50),
    -- 값별 가중치(합 100)
    ('feed.weight.3', 45),
    ('feed.weight.5', 35),
    ('feed.weight.10', 17),
    ('feed.weight.50', 3),
    ('save-store', 2),
    ('save-store.daily-cap', 3),
    ('letter', 10),
    ('letter.max-unread', 4),
    ('ad', 20),
    ('ad.daily-cap', 2),
    ('invite', 100),
    ('invite.inviter-cap', 10),
    ('invite.expire-days', 30),
    ('tour.visit', 3),
    ('tour.visit.daily-cap', 8),
    ('tour.complete', 30),
    ('tour.photo-complete', 30),
    ('ranking.first', 100),
    ('ranking.second-third', 50),
    ('ranking.fourth-tenth', 20),
    ('wake-friend', 600),
    ('carry-over.base', 50),
    ('carry-over.per-record', 5),
    ('carry-over.max', 300);
