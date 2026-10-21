package sweetgirl.croissant.course.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.dto.projection.CourseStoreSummaryProjection;
import sweetgirl.croissant.course.entity.CourseStore;

public interface CourseStoreRepository extends JpaRepository<CourseStore, Long> {

	/**
	 * 한 코스의 활성 정류장. 상세 조회와 가게 추가·순서 변경 같은 쓰기 경로가 같이 쓰므로 Store 를 fetch 하지 않는다 —
	 * 쓰기는 Store 컬럼이 필요 없고, 상세의 Store 는 Store 의 @BatchSize 가 IN 한 방으로 읽는다.
	 */
	List<CourseStore> findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(Long courseId);

	/**
	 * 목록 화면용 — 코스별 정류장의 가게 id·이름·지역만 한 번에. Store 엔티티를 싣지 않는다(geography 위치 등 15개 컬럼).
	 * 코스 id·방문 순서로 정렬해 소비처가 코스별로 갈라 담고 순서를 그대로 쓴다.
	 */
	@Query("""
			SELECT cs.course.id AS courseId, s.id AS storeId, s.name AS storeName,
				s.region1st AS region1st, s.region2nd AS region2nd
			FROM CourseStore cs JOIN cs.store s
			WHERE cs.course.id IN :courseIds AND cs.deletedAt IS NULL
			ORDER BY cs.course.id, cs.visitOrder
			""")
	List<CourseStoreSummaryProjection> findSummariesByCourseIdIn(@Param("courseIds") Collection<Long> courseIds);

	Optional<CourseStore> findByCourseIdAndStoreIdAndDeletedAtIsNull(Long courseId, Long storeId);

	boolean existsByCourseIdAndStoreIdAndDeletedAtIsNull(Long courseId, Long storeId);

	long countByCourseIdAndDeletedAtIsNull(Long courseId);

	@Query("SELECT COALESCE(MAX(cs.visitOrder), 0) FROM CourseStore cs"
		+ " WHERE cs.course.id = :courseId AND cs.deletedAt IS NULL")
	int findMaxVisitOrderByCourseId(@Param("courseId") Long courseId);

	/** 일차 수 검증용 — 가게가 있는 마지막 일차. 정류장을 전부 읽지 않는다. 가게가 없으면 0. */
	@Query("SELECT COALESCE(MAX(cs.day), 0) FROM CourseStore cs"
		+ " WHERE cs.course.id = :courseId AND cs.deletedAt IS NULL")
	int findMaxDayByCourseId(@Param("courseId") Long courseId);
}
