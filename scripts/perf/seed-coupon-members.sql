-- 선착순 쿠폰 부하 측정용 회원 시드.
--
-- 쿠폰은 1인 1매(uq_coupon_issue)이고 coupon_issues.member_id 에 FK 가 걸려 있다.
-- 그래서 "5만 명이 몰린다"를 재현하려면 실제 member 행이 5만 개 있어야 한다.
--
-- 사용 (랩 PG 에만 — 운영 터널 15432 에 붙이지 않는다):
--   docker exec -i postgres-lab psql -U sweetgirl -d soboro -v target=50000 \
--     -f - < scripts/perf/seed-coupon-members.sql
--   (또는 아래 \set 기본값 그대로 쓰려면 -v 없이 실행)
--
-- 여러 번 돌려도 안전하다. 이미 있는 만큼은 건너뛰고 모자란 만큼만 넣는다.

\set ON_ERROR_STOP on
\if :{?target}
\else
\set target 50000
\endif

INSERT INTO member (nickname, created_at, updated_at, member_type)
SELECT '[부하]회원' || g, now(), now(), 'USER'
FROM generate_series(
    (SELECT count(*) FROM member WHERE nickname LIKE '[부하]회원%')::int + 1,
    :target
) AS g;

SELECT
    (SELECT count(*) FROM member WHERE nickname LIKE '[부하]회원%') AS 부하테스트_회원,
    (SELECT count(*) FROM member) AS 전체_회원;

-- 정리할 때 (측정이 끝나고 랩을 되돌리지 않을 경우):
--   DELETE FROM coupon_issues WHERE member_id IN (SELECT id FROM member WHERE nickname LIKE '[부하]회원%');
--   DELETE FROM member WHERE nickname LIKE '[부하]회원%';
-- 랩은 운영 덤프 복제본이라 scripts/lab/restore-lab.sh 로 통째로 되돌리는 쪽이 확실하다.
