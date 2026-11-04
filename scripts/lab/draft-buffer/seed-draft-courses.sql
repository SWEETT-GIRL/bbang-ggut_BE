-- 메모 초안 버퍼 부하 측정용 시드 — 협업 코스 N개 × 멤버 8명 × 정류장 5개.
--
-- 사용 (랩 PG 에만 — 운영 터널 15432 에 붙이지 않는다):
--   docker exec -i postgres-lab psql -U sweetgirl -d soboro -v courses=50 -f - < scripts/lab/draft-buffer/seed-draft-courses.sql
--
-- 여러 번 돌려도 안전하다. 이미 있는 코스는 두고 모자란 만큼만 만든다. 정류장은 랩 덤프의 실제 가게 중 임의 5곳.
-- 회원은 '[초안]회원N', 코스는 '[초안]코스N' — 정리는 아래 주석.

\set ON_ERROR_STOP on
\if :{?courses}
\else
\set courses 50
\endif
\set members_per_course 8
\set stops_per_course 5

-- 회원
INSERT INTO member (nickname, nickname_normalized, created_at, updated_at, member_type)
SELECT '[초안]회원' || g, lower('[초안]회원' || g), now(), now(), 'USER'
FROM generate_series(
    (SELECT count(*) FROM member WHERE nickname LIKE '[초안]회원%')::int + 1,
    :courses * :members_per_course
) AS g;

-- 코스 — 방장은 그 코스의 첫 회원
WITH numbered_members AS (
    SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
    FROM member WHERE nickname LIKE '[초안]회원%'
),
new_courses AS (
    SELECT g AS course_no, (SELECT id FROM numbered_members WHERE n = (g - 1) * :members_per_course) AS owner_id
    FROM generate_series(
        (SELECT count(*) FROM courses WHERE title LIKE '[초안]코스%')::int + 1,
        :courses
    ) AS g
)
INSERT INTO courses (type, title, member_id, is_public, visibility, day_count, created_at, updated_at)
SELECT 'CUSTOM', '[초안]코스' || course_no, owner_id, false, 'PRIVATE', 1, now(), now()
FROM new_courses;

-- 멤버 행 (방장 포함 8명)
WITH numbered_members AS (
    SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
    FROM member WHERE nickname LIKE '[초안]회원%'
),
numbered_courses AS (
    SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
    FROM courses WHERE title LIKE '[초안]코스%' AND deleted_at IS NULL
)
INSERT INTO course_members (course_id, member_id, joined_at, created_at, updated_at)
SELECT c.id, m.id, now() + (slot || ' seconds')::interval, now(), now()
FROM numbered_courses c
CROSS JOIN generate_series(0, :members_per_course - 1) AS slot
JOIN numbered_members m ON m.n = c.n * :members_per_course + slot
ON CONFLICT DO NOTHING;

-- 정류장 — 코스마다 임의 가게 5곳
WITH numbered_courses AS (
    SELECT id FROM courses WHERE title LIKE '[초안]코스%' AND deleted_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM course_stores cs WHERE cs.course_id = courses.id)
),
picks AS (
    SELECT c.id AS course_id, s.id AS store_id,
           row_number() OVER (PARTITION BY c.id ORDER BY random()) AS visit_order
    FROM numbered_courses c
    CROSS JOIN LATERAL (
        SELECT id FROM store WHERE status <> 'DELETED' ORDER BY random() LIMIT :stops_per_course
    ) s
)
INSERT INTO course_stores (course_id, store_id, visit_order, day, created_at, updated_at)
SELECT course_id, store_id, visit_order, 1, now(), now()
FROM picks;

SELECT
    (SELECT count(*) FROM courses WHERE title LIKE '[초안]코스%' AND deleted_at IS NULL) AS 코스,
    (SELECT count(*) FROM course_members cm JOIN courses c ON c.id = cm.course_id WHERE c.title LIKE '[초안]코스%') AS 멤버행,
    (SELECT count(*) FROM course_stores cs JOIN courses c ON c.id = cs.course_id WHERE c.title LIKE '[초안]코스%') AS 정류장;

-- k6 가 읽는 매핑 (courseId,storeIds(|구분),memberIds(|구분)) — 헤더 없음:
--   docker exec postgres-lab psql -U sweetgirl -d soboro -At -F, -c "
--     SELECT c.id,
--            (SELECT string_agg(store_id::text, '|' ORDER BY visit_order) FROM course_stores WHERE course_id = c.id AND deleted_at IS NULL),
--            (SELECT string_agg(member_id::text, '|' ORDER BY joined_at) FROM course_members WHERE course_id = c.id AND deleted_at IS NULL)
--     FROM courses c WHERE c.title LIKE '[초안]코스%' AND c.deleted_at IS NULL ORDER BY c.id" > scripts/lab/draft-buffer/courses.csv
-- 토큰:
--   docker exec postgres-lab psql -U sweetgirl -d soboro -At -c "SELECT id FROM member WHERE nickname LIKE '[초안]회원%' ORDER BY id" > scripts/lab/draft-buffer/member-ids.txt
--   node scripts/perf/generate-tokens.mjs scripts/lab/draft-buffer/member-ids.txt scripts/lab/draft-buffer/tokens.csv
--
-- 정리:
--   DELETE FROM course_store_memos WHERE course_store_id IN (SELECT cs.id FROM course_stores cs JOIN courses c ON c.id = cs.course_id WHERE c.title LIKE '[초안]코스%');
--   DELETE FROM course_stores WHERE course_id IN (SELECT id FROM courses WHERE title LIKE '[초안]코스%');
--   DELETE FROM course_members WHERE course_id IN (SELECT id FROM courses WHERE title LIKE '[초안]코스%');
--   DELETE FROM courses WHERE title LIKE '[초안]코스%';
--   DELETE FROM member WHERE nickname LIKE '[초안]회원%';
