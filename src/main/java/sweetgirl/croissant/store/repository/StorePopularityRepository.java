package sweetgirl.croissant.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.Store;

/**
 * 가게 인기도(store.popularity_score) 갱신 전용 저장소.
 *
 * <p>산식은 "구독수 + Σ max(0, 51-전국랭킹순위) + STORE_VIEW 조회수". 가게를 한 건씩 돌지 않고 집계 UPDATE 한 방으로,
 * 값이 실제로 바뀐 행만(IS DISTINCT FROM) 갱신한다. 검색 엔진이 랭킹·인기순 정렬에 이 컬럼을 쓴다.
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

	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE store s
			SET popularity_score = COALESCE(sub.cnt, 0) + COALESCE(rk.score, 0) + COALESCE(vw.cnt, 0)
			FROM store st
			LEFT JOIN (SELECT store_id, count(*)::float8 AS cnt FROM subscribed_store GROUP BY store_id) sub
				ON sub.store_id = st.id
			LEFT JOIN (SELECT store_id, sum(greatest(0, 51 - rank))::float8 AS score
						FROM nationwide_hot_store_ranking GROUP BY store_id) rk
				ON rk.store_id = st.id
			LEFT JOIN (SELECT store_id, count(*)::float8 AS cnt FROM request_log
						WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL GROUP BY store_id) vw
				ON vw.store_id = st.id
			WHERE s.id = st.id
				AND s.popularity_score IS DISTINCT FROM
					(COALESCE(sub.cnt, 0) + COALESCE(rk.score, 0) + COALESCE(vw.cnt, 0))
			""", nativeQuery = true)
	int refreshAllPopularityScores();
}
