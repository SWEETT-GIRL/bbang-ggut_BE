package sweetgirl.croissant.store.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.Store;

/**
 * 가게 인기도(store.popularity_score) 갱신 전용 저장소.
 *
 * <p>산식(#663 ③, 2026-09-18): <b>행동 점수 + 랭킹 점수 × 배율</b>. ACTIVE 가게만 계산한다.
 * <ul>
 * <li>행동 점수 = 상세를 본 회원 수(distinct) × min(1, 상대 전환율) + 찜 수 + 빵투어에 담은 회원 수(distinct). 상대 전환율 = ((찜 +
 * 담기) + k·p0) ÷ (조회 회원 + k) ÷ p0, p0 = 전체 (찜 + 담기) ÷ 전체 조회 회원(같은 문장 안에서 계산). 유명해서 많이 보지만 안 담기는
 * 가게의 조회는 그 비율만큼 깎이고, 상세를 안 보고 담긴 가게도 담기 수만큼 받는다. 조회·찜·담기 모두 사람 단위다 — 한 회원이 같은 가게를
 * 여러 코스에 담거나 포크해도 한 번.</li>
 * <li>랭킹 점수 = Σ max(0, 51 − 전국 유명 빵 카테고리 순위). 6월 스냅샷이라 행동 점수와 눈금이 달라, 두 분포(0 초과 가게)의 같은 분위
 * 값이 같아지도록 배율을 곱한다(운영 09-18: 행동 p95 5 · 랭킹 p95 49 → 0.102). 행동 분포가 아직 없으면 원값(배율 1), 랭킹이 없으면
 * 0.</li>
 * <li>세지 않는 것: 익명 조회(회원 id 없음), 4xx·5xx 응답, 삭제된 코스·삭제된 담기, 큐레이션 코스(주인 없음), 구독(더 쓰지 않는 기능).
 * 이전 산식(조회 행 수)은 운영 조회 38,598행 중 익명 26,759행이고 그중 22,713행은 dataops 보강 스크립트가 관리자 API 를 부른 것이 STORE_VIEW
 * 로 잡힌 것이라 회원 0명이 본 가게가 상위에 올랐다.</li>
 * </ul>
 * 가게를 한 건씩 돌지 않고 집계 UPDATE 한 방으로, 값이 실제로 바뀐 행만(IS DISTINCT FROM) 갱신한다. 검색 엔진(관련도 배수 · 인기순
 * 정렬)과 추천 후보 prior 가 이 컬럼을 쓴다. 첫 적용은 마이그레이션 V20260918180000 이 같은 산식(k=10 · q=0.95)으로 한 번 돌린다.
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
	 * @param smoothing 전환율 보정의 스무딩 표본 수 k(1 이상). p0 가 0 이면(찜·담기가 하나도 없으면) 보정 없이 조회를 그대로 센다
	 * @param quantile 랭킹 배율을 맞추는 분위(0.5~1)
	 * @return 값이 바뀐 가게 수
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			WITH rk AS (SELECT store_id, sum(greatest(0, 51 - rank))::float8 AS score
					FROM nationwide_hot_store_ranking GROUP BY store_id),
			vw AS (SELECT store_id, count(DISTINCT member_id)::float8 AS members FROM request_log
					WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL AND member_id IS NOT NULL
						AND response_status < 400
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
						THEN LEAST(1.0, (s.conv + :smoothing * base.p0) / NULLIF(s.members + :smoothing, 0) / base.p0)
						ELSE 1 END
					+ s.conv AS score
				FROM signals s CROSS JOIN base),
			scale AS (SELECT
					(SELECT percentile_cont(CAST(:quantile AS float8)) WITHIN GROUP (ORDER BY score)
						FROM behavior WHERE score > 0) AS behavior_q,
					(SELECT percentile_cont(CAST(:quantile AS float8)) WITHIN GROUP (ORDER BY rank_score)
						FROM behavior WHERE rank_score > 0) AS rank_q),
			scored AS (
				SELECT b.id, b.score + b.rank_score * CASE
					WHEN scale.behavior_q IS NULL THEN 1
					WHEN scale.rank_q IS NULL OR scale.rank_q = 0 THEN 0
					ELSE scale.behavior_q / scale.rank_q END AS score
				FROM behavior b CROSS JOIN scale)
			UPDATE store s
			SET popularity_score = scored.score
			FROM scored
			WHERE s.id = scored.id AND s.popularity_score IS DISTINCT FROM scored.score
			""", nativeQuery = true)
	int refreshAllPopularityScores(@Param("smoothing") int smoothing, @Param("quantile") double quantile);

	/** ACTIVE · 0 초과 가게의 인기도 분위 — 갱신 뒤 로그로 남겨 추천 prior 상한(popularityCap)을 다시 볼 때를 알린다. */
	@Query(value = """
			SELECT COALESCE(percentile_cont(CAST(:quantile AS float8)) WITHIN GROUP (ORDER BY popularity_score), 0)
			FROM store WHERE status = 'ACTIVE' AND popularity_score > 0
			""", nativeQuery = true)
	double findActivePopularityQuantile(@Param("quantile") double quantile);
}
