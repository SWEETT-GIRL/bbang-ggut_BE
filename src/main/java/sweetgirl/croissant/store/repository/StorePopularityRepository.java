package sweetgirl.croissant.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.store.entity.Store;

/**
 * 가게 인기도(store.popularity_score) 갱신 전용 저장소.
 *
 * <p>산식은 "구독수 + Σ max(0, 51-전국랭킹순위) + STORE_VIEW 조회수". 가게를 한 건씩 돌지 않고 집계 UPDATE 한 방으로 갱신한다
 * (45k 가게 기준 수 초). 검색 엔진이 랭킹·인기순 정렬에 이 컬럼을 쓴다.
 */
public interface StorePopularityRepository extends JpaRepository<Store, Long> {

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
			""", nativeQuery = true)
	int refreshAllPopularityScores();
}
