package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.CourseStore;

public interface CourseStoreRepository extends JpaRepository<CourseStore, Long> {

	List<CourseStore> findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(Long courseId);

	List<CourseStore> findAllByCourseIdInAndDeletedAtIsNullOrderByVisitOrderAsc(List<Long> courseIds);

	Optional<CourseStore> findByCourseIdAndStoreIdAndDeletedAtIsNull(Long courseId, Long storeId);

	boolean existsByCourseIdAndStoreIdAndDeletedAtIsNull(Long courseId, Long storeId);

	long countByCourseIdAndDeletedAtIsNull(Long courseId);

	@Query("SELECT COALESCE(MAX(cs.visitOrder), 0) FROM CourseStore cs"
		+ " WHERE cs.course.id = :courseId AND cs.deletedAt IS NULL")
	int findMaxVisitOrderByCourseId(@Param("courseId") Long courseId);
}
