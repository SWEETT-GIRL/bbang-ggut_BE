package sweetgirl.croissant.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.Store;

/**
 * 가게 인기도(store.popularity_score) 갱신 전용 저장소.
 *
 * <p>산식(#663 ③, 2026-09-18): <b>구독 수 + Σ max(0, 51 − 전국 랭킹 순위) + 회원 distinct 조회 수 × 상대 전환율</b>.
 * 상대 전환율 = ((저장 + 담기) + k·p0) ÷ (조회 회원 + k) ÷ p0, p0 = 전체 (저장 + 담기) ÷ 전체 조회 회원(같은 문장 안에서 계산),
 * k 는 스무딩 표본 수(설정). 익명 조회는 세지 않는다 — 이전 산식(조회 행 수)은 운영 조회 37,553행 중 익명 26,245행, 그중 22,291행이 IP
 * 하나(크롤러)라 회원 0명이 본 가게가 상위에 올랐다. 가게를 한 건씩 돌지 않고 집계 UPDATE 한 방으로, 값이 실제로 바뀐 행만(IS DISTINCT
 * FROM) 갱신한다. 검색 엔진(관련도 배수 · 인기순 정렬)과 추천 후보 prior 가 이 컬럼을 쓴다.
 */
public interface StorePopularityRepository extends JpaRepository<Store, Long> {

	/**
	 * 인기도 갱신 전용 트랜잭션 advisory lock. 같은 트랜잭션 안에서 UPDATE 앞에 호출한다 — 어드민 수동 갱신과 주간 배치,
	 * 다중 인스턴스의 @Scheduled 가 겹쳐도 45k행 UPDATE 가 동시에 두 번 돌지 않게 한다(락은 커밋/롤백 시 자동 해제).
	 *
	 * @return 락을 얻었으면 true, 다른 갱신이 진행 중이면 false
	 */
	@Query(value = "SELECT pg_try_advisory_xact_lock(:lockKey)", nativeQuery = true)
	boolean tryLockPopularityRefresh(@Param("lockKey") long lockKey);

	/**
	 * @param smoothing 상대 전환율의 스무딩 표본 수 k. 전체 전환율 p0 가 0 이면(저장·담기가 하나도 없으면) 비율 1 로 둔다
	 * @return 값이 바뀐 가게 수
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			WITH sub AS (SELECT store_id, count(*)::float8 AS cnt FROM subscribed_store GROUP BY store_id),
			rk AS (SELECT store_id, sum(greatest(0, 51 - rank))::float8 AS score
					FROM nationwide_hot_store_ranking GROUP BY store_id),
			vw AS (SELECT store_id, count(DISTINCT member_id)::float8 AS members FROM request_log
					WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL AND member_id IS NOT NULL
					GROUP BY store_id),
			conv AS (SELECT store_id, count(*)::float8 AS n FROM (
						SELECT store_id FROM saved_stores
						UNION ALL
						SELECT cs.store_id FROM course_stores cs JOIN courses c ON c.id = cs.course_id
						WHERE c.member_id IS NOT NULL AND cs.deleted_at IS NULL) x
					GROUP BY store_id),
			base AS (SELECT COALESCE((SELECT sum(n) FROM conv) / NULLIF((SELECT sum(members) FROM vw), 0), 0) AS p0),
			scored AS (
				SELECT st.id,
					COALESCE(sub.cnt, 0) + COALESCE(rk.score, 0)
						+ COALESCE(vw.members, 0) * CASE WHEN base.p0 > 0
							THEN ((COALESCE(conv.n, 0) + :smoothing * base.p0) / (COALESCE(vw.members, 0) + :smoothing))
								/ base.p0
							ELSE 1 END AS score
				FROM store st
				LEFT JOIN sub ON sub.store_id = st.id
				LEFT JOIN rk ON rk.store_id = st.id
				LEFT JOIN vw ON vw.store_id = st.id
				LEFT JOIN conv ON conv.store_id = st.id
				CROSS JOIN base)
			UPDATE store s
			SET popularity_score = scored.score
			FROM scored
			WHERE s.id = scored.id AND s.popularity_score IS DISTINCT FROM scored.score
			""", nativeQuery = true)
	int refreshAllPopularityScores(@Param("smoothing") int smoothing);
}
