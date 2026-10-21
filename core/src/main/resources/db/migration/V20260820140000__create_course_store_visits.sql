-- 코스 방문 체크를 빵로그 기록과 명시적으로 잇는다.
--
-- 지금까지 방문 여부는 저장된 값이 아니라 "그 가게 기록이 하나라도 있으면 방문"으로
-- 매번 계산되는 파생값이었다. 그래서 4달 전에 갔던 빵집이 코스에 들어가면
-- 코스를 시작하자마자 체크가 켜졌고, 재방문은 아무 변화도 만들지 못했다.
-- 방문을 파생값이 아니라 사건으로 저장해 시간축(언제)과 코스축(어느 코스)을 되찾는다.
--
-- course_stores.id 가 아니라 (course_id, store_id) 를 키로 쓴다.
-- 가게를 뺐다가 다시 담으면 course_stores 행이 새로 생겨 id가 바뀌는데,
-- 자연키면 그때도 방문 기록이 살아남는다. 마침 uq_course_stop_store 로
-- (course_id, store_id) 유니크가 이미 보장돼 있다.
CREATE TABLE course_store_visits (
    course_id       BIGINT    NOT NULL REFERENCES courses (id),
    member_id       BIGINT    NOT NULL REFERENCES member (id),
    store_id        BIGINT    NOT NULL REFERENCES store (id),
    -- 기록 없이 체크만 하는 경로를 나중에 열 수 있게 nullable 로 둔다. 지금은 항상 채운다.
    bread_record_id BIGINT    REFERENCES bread_record (id),
    visited_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP,
    PRIMARY KEY (course_id, member_id, store_id)
);

CREATE INDEX idx_course_store_visits_member_course
    ON course_store_visits (member_id, course_id)
    WHERE deleted_at IS NULL;

-- 기록을 지우면 그 기록으로 켜둔 체크도 함께 정리해야 한다. 역방향 조회용.
CREATE INDEX idx_course_store_visits_bread_record
    ON course_store_visits (bread_record_id)
    WHERE deleted_at IS NULL AND bread_record_id IS NOT NULL;

-- 지금 켜져 있는 체크를 하나도 끄지 않는다.
--
-- 누가 체크를 보고 있는가 = 앱의 courseStarted 조건과 같아야 한다.
--   const courseStarted = isMine || courseComplete || progresses.some(...)
-- 즉 (1) 코스 주인은 따라가기를 시작하지 않아도 항상 보고 있고,
--    (2) 따라가기를 시작한 사람이 본다.
-- 주인을 빼먹으면 "내가 만든 코스"의 체크가 전부 꺼진다. 실제로 주인에게는
-- 시작 버튼이 노출되지 않아 course_progress 행이 아예 없다.
WITH viewer AS (
    SELECT cp.course_id, cp.member_id
    FROM course_progress cp
    UNION
    SELECT c.id, c.member_id
    FROM courses c
    WHERE c.member_id IS NOT NULL
      AND c.deleted_at IS NULL
)
INSERT INTO course_store_visits
    (course_id, member_id, store_id, bread_record_id, visited_at, created_at)
SELECT cs.course_id, v.member_id, cs.store_id, r.id, r.eaten_at, NOW()
FROM viewer v
JOIN courses c
    ON c.id = v.course_id
   AND c.deleted_at IS NULL
JOIN course_stores cs
    ON cs.course_id = v.course_id
   AND cs.deleted_at IS NULL
JOIN LATERAL (
    SELECT id, eaten_at
    FROM bread_record
    WHERE member_id = v.member_id
      AND store_id  = cs.store_id
      AND deleted_at IS NULL
    ORDER BY eaten_at DESC
    LIMIT 1
) r ON TRUE
ON CONFLICT (course_id, member_id, store_id) DO NOTHING;
