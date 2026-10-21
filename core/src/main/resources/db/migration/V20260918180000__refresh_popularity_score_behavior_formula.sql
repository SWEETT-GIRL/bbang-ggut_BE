-- 인기도 산식 변경(#663 ③)의 1회 적용. 코드(StorePopularityRepository.refreshAllPopularityScores, k=10 · q=0.95)와 같은 산식이라
-- 배포와 동시에 점수 눈금이 바뀐다 — 추천 prior 상한(popularityCap 4.3)이 옛 눈금(최대 251)에 걸려 포화하는 창을 없앤다.
-- 이후는 주간 배치(StorePopularityScoreScheduler)와 어드민 수동 갱신이 덮어쓴다. 값이 바뀌는 행만 써서 재실행에 안전하다.
WITH rk AS (SELECT store_id, sum(greatest(0, 51 - rank))::float8 AS score
        FROM nationwide_hot_store_ranking GROUP BY store_id),
vw AS (SELECT store_id, count(DISTINCT member_id)::float8 AS members FROM request_log
        WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL AND member_id IS NOT NULL AND response_status < 400
        GROUP BY store_id),
sv AS (SELECT store_id, count(*)::float8 AS cnt FROM saved_stores GROUP BY store_id),
ca AS (SELECT cs.store_id, count(DISTINCT c.member_id)::float8 AS cnt
        FROM course_stores cs JOIN courses c ON c.id = cs.course_id
        WHERE c.member_id IS NOT NULL AND c.deleted_at IS NULL AND cs.deleted_at IS NULL
        GROUP BY cs.store_id),
signals AS (
    SELECT st.id, COALESCE(rk.score, 0) AS rank_score, COALESCE(vw.members, 0) AS members,
        COALESCE(sv.cnt, 0) + COALESCE(ca.cnt, 0) AS conv
    FROM store st
    LEFT JOIN rk ON rk.store_id = st.id
    LEFT JOIN vw ON vw.store_id = st.id
    LEFT JOIN sv ON sv.store_id = st.id
    LEFT JOIN ca ON ca.store_id = st.id
    WHERE st.status = 'ACTIVE'),
base AS (SELECT COALESCE(sum(conv) / NULLIF(sum(members), 0), 0) AS p0 FROM signals),
behavior AS (
    SELECT s.id, s.rank_score,
        s.members * CASE WHEN base.p0 > 0
            THEN LEAST(1.0, (s.conv + 10 * base.p0) / NULLIF(s.members + 10, 0) / base.p0)
            ELSE 1 END
        + s.conv AS score
    FROM signals s CROSS JOIN base),
scale AS (SELECT
        (SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY score) FROM behavior WHERE score > 0) AS behavior_q,
        (SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY rank_score) FROM behavior WHERE rank_score > 0) AS rank_q),
scored AS (
    SELECT b.id, b.score + b.rank_score * CASE
        WHEN scale.behavior_q IS NULL THEN 1
        WHEN scale.rank_q IS NULL OR scale.rank_q = 0 THEN 0
        ELSE scale.behavior_q / scale.rank_q END AS score
    FROM behavior b CROSS JOIN scale)
UPDATE store s
SET popularity_score = scored.score
FROM scored
WHERE s.id = scored.id AND s.popularity_score IS DISTINCT FROM scored.score;
