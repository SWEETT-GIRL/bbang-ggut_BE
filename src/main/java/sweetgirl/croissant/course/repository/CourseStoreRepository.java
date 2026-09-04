package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.CourseStore;

public interface CourseStoreRepository extends JpaRepository<CourseStore, Long> {

	/**
	 * 한 코스의 활성 정류장. 상세 조회와 가게 추가·순서 변경 같은 쓰기 경로가 같이 쓰므로 Store 를 fetch 하지 않는다 —
	 * 쓰기는 Store 컬럼이 필요 없고, 상세의 Store 는 default_batch_fetch_size 가 IN 한 방으로 읽는다.
	 */
	List<CourseStore> findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(Long courseId);

	/** 목록 화면용. 코스마다 가게 이름·지역·썸네일을 읽으니 Store 를 같이 가져온다 — 정류장 수만큼 SELECT 가 나가던 N+1. */
	@EntityGraph(attributePaths = "store")
	List<CourseStore> findAllByCourseIdInAndDeletedAtIsNullOrderByVisitOrderAsc(List<Long> courseIds);

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
