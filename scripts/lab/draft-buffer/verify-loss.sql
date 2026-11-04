-- 페일오버 뒤 유실 판정.
--
-- k6 가 보낸 글은 content 에 "f<memberId> <보낸 ms> <난수>" 로 시각이 박혀 있고, seq 는 보낸 ms 이상이다(seq 시계).
-- 앱의 약속이 지켜졐으면 각 (정류장, 회원) 칸에 "마지막으로 서버가 200 을 준 글" 이 DB 에 있어야 한다.
-- 정확한 마지막 seq 는 k6 VU 안에 있어 밖으로 못 나오므로, 여기서는 두 가지로 본다:
--   (1) Redis 잔량 — 플러시 상한(10초) + 여유가 지난 뒤에도 draft:due / draft:{c} 에 남은 게 있으면 반영이 막힌 것
--       redis-cli -p 6380 ZCARD draft:due  (승격 뒤라면 새 마스터 포트)
--   (2) DB 의 각 칸이 "측정 종료 직전" 글인지 — 마지막 글의 시각이 종료 시각보다 interval 이상 뒤처진 칸을 센다.
--       PROMISE=off 에서는 포기한 글만큼 뒤처진 칸이 생기고, PROMISE=on 이면 0 이어야 한다.
--
--   docker exec postgres-lab psql -U sweetgirl -d soboro -v ended_at="'2026-11-04 15:30:00'" -v interval="'3 seconds'" \
--     -f - < scripts/lab/draft-buffer/verify-loss.sql
-- ended_at 은 k6 editors 시나리오가 끝난 시각(k6 출력의 끝 시각, KST 로). interval 은 쓰기 간격(0.5초) × 여유.

\set ON_ERROR_STOP on
\if :{?ended_at}
\else
\echo 'ended_at 을 -v 로 넘겨야 한다'
\quit 1
\endif
\if :{?interval}
\else
\set interval '''3 seconds'''
\endif

WITH probe_memos AS (
    SELECT csm.course_store_id, csm.member_id, csm.content, csm.seq,
           to_timestamp(split_part(csm.content, ' ', 2)::bigint / 1000.0) AT TIME ZONE 'Asia/Seoul' AS sent_at
    FROM course_store_memos csm
    JOIN course_stores cs ON cs.id = csm.course_store_id
    JOIN courses c ON c.id = cs.course_id
    WHERE c.title LIKE '[초안]코스%' AND csm.deleted_at IS NULL AND csm.content LIKE 'f%'
)
SELECT
    count(*) AS 칸,
    count(*) FILTER (WHERE sent_at < :ended_at::timestamp - :interval::interval) AS 뒤처진_칸,
    min(sent_at) AS 가장_오래된_마지막_글,
    max(sent_at) AS 가장_최근_글
FROM probe_memos;

-- 뒤처진 칸 상세 (있다면)
WITH probe_memos AS (
    SELECT csm.course_store_id, csm.member_id, csm.content, csm.seq,
           to_timestamp(split_part(csm.content, ' ', 2)::bigint / 1000.0) AT TIME ZONE 'Asia/Seoul' AS sent_at
    FROM course_store_memos csm
    JOIN course_stores cs ON cs.id = csm.course_store_id
    JOIN courses c ON c.id = cs.course_id
    WHERE c.title LIKE '[초안]코스%' AND csm.deleted_at IS NULL AND csm.content LIKE 'f%'
)
SELECT course_store_id, member_id, seq, sent_at
FROM probe_memos
WHERE sent_at < :ended_at::timestamp - :interval::interval
ORDER BY sent_at
LIMIT 20;
