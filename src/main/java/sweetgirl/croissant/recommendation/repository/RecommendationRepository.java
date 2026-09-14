package sweetgirl.croissant.recommendation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.enums.RecommendationMode;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

	/**
	 * 재사용 키(상권 추천): 같은 회원·코스·상권·일차·프로필 버전의 최신 한 건. idx_recommendation_reuse 를 탄다.
	 * 회원이 키에 있는 이유: 코스는 공동 편집이라 같은 코스를 여러 사람이 보는데, 추천은 그 사람의 취향으로 계산한 것이고
	 * 프로필 버전은 사람마다 따로 세는 값이라 회원 없이는 남의 추천이 잡힌다.
	 */
	@Query("""
			SELECT r FROM Recommendation r
			WHERE r.memberId = :memberId AND r.courseId = :courseId
				AND r.mode = sweetgirl.croissant.recommendation.enums.RecommendationMode.AREA
				AND r.courseAreaId = :courseAreaId AND r.day = :day AND r.profileVersion = :profileVersion
			ORDER BY r.createdAt DESC
			LIMIT 1
			""")
	Optional<Recommendation> findLatestAreaForReuse(@Param("memberId") Long memberId, @Param("courseId") Long courseId,
			@Param("courseAreaId") Long courseAreaId, @Param("day") Short day,
			@Param("profileVersion") int profileVersion);

	/** 재사용 키(앵커 추천): 같은 회원·코스·앵커·일차·프로필 버전. 일차는 앵커 가게가 담긴 일차다 — 앵커를 다른 일차로 옮기면 그 일차 기준으로 다시 만든다. */
	@Query("""
			SELECT r FROM Recommendation r
			WHERE r.memberId = :memberId AND r.courseId = :courseId
				AND r.mode = sweetgirl.croissant.recommendation.enums.RecommendationMode.ANCHOR
				AND r.anchorStoreId = :anchorStoreId AND r.day = :day AND r.profileVersion = :profileVersion
			ORDER BY r.createdAt DESC
			LIMIT 1
			""")
	Optional<Recommendation> findLatestAnchorForReuse(@Param("memberId") Long memberId,
			@Param("courseId") Long courseId,
			@Param("anchorStoreId") Long anchorStoreId, @Param("day") Short day,
			@Param("profileVersion") int profileVersion);

	/** 피드백·bulk 담기의 소유 확인 — 남의 추천은 못 찾은 것으로 본다. */
	Optional<Recommendation> findByIdAndMemberId(Long id, Long memberId);

	/**
	 * 다시 추천(#661)의 제외 재료: 이 회원·코스·상권 행·일차의 상권 추천 전부(프로필 버전 무관 — 프로필이 바뀌어도 본 가게는 다시
	 * 안 보인다). 한 키에 행이 몇 개 안 돼 result 의 stores 는 자바에서 읽는다(jsonb 함수 없이 — H2 로도 검증된다).
	 */
	List<Recommendation> findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(Long memberId, Long courseId,
			RecommendationMode mode, Long courseAreaId, Short day);

	/** 다음 칸 제안(#661)에서 "이 코스에서 이미 추천받은 상권 행"을 뺀다 — 일차와 무관. */
	@Query("""
			SELECT DISTINCT r.courseAreaId FROM Recommendation r
			WHERE r.memberId = :memberId AND r.courseId = :courseId
				AND r.mode = sweetgirl.croissant.recommendation.enums.RecommendationMode.AREA
			""")
	List<Long> findRecommendedAreaIds(@Param("memberId") Long memberId, @Param("courseId") Long courseId);
}
