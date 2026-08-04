package sweetgirl.croissant.course.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.dto.projection.CourseProgressAggregateProjection;
import sweetgirl.croissant.course.dto.projection.CourseProgressMemberAggregateProjection;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.entity.CourseProgressId;

public interface CourseProgressRepository extends JpaRepository<CourseProgress, CourseProgressId> {

	@EntityGraph(attributePaths = "course")
	List<CourseProgress> findAllByMemberId(Long memberId);

	@EntityGraph(attributePaths = "course")
	Optional<CourseProgress> findByMemberIdAndCourseId(Long memberId, Long courseId);

	List<CourseProgress> findAllByCourseId(Long courseId);

	@Query(value = """
			SELECT
				progress.course_id AS courseId,
				COUNT(DISTINCT course_store.store_id) AS storeCount,
				COUNT(DISTINCT record.store_id) AS visitedStoreCount,
				MAX(record.created_at) AS lastTouchedAt
			FROM course_progress progress
			LEFT JOIN course_stores course_store
				ON course_store.course_id = progress.course_id
				AND course_store.deleted_at IS NULL
			LEFT JOIN bread_record record
				ON record.member_id = progress.member_id
				AND record.store_id = course_store.store_id
				AND record.deleted_at IS NULL
			WHERE progress.member_id = :memberId
			AND progress.course_id IN (:courseIds)
			GROUP BY progress.course_id
			""", nativeQuery = true)
	List<CourseProgressAggregateProjection> findAggregates(
			@Param("memberId") Long memberId,
			@Param("courseIds") Collection<Long> courseIds);

	@Query(value = """
			SELECT
				progress.member_id AS memberId,
				COUNT(DISTINCT course_store.store_id) AS storeCount,
				COUNT(DISTINCT record.store_id) AS visitedStoreCount
			FROM course_progress progress
			LEFT JOIN course_stores course_store
				ON course_store.course_id = progress.course_id
				AND course_store.deleted_at IS NULL
			LEFT JOIN bread_record record
				ON record.member_id = progress.member_id
				AND record.store_id = course_store.store_id
				AND record.deleted_at IS NULL
			WHERE progress.course_id = :courseId
			GROUP BY progress.member_id
			""", nativeQuery = true)
	List<CourseProgressMemberAggregateProjection> findMemberAggregates(@Param("courseId") Long courseId);

	@Query("""
			SELECT cp.course.id, COUNT(cp)
			FROM CourseProgress cp
			WHERE cp.course.id IN :courseIds
			AND cp.completedAt IS NOT NULL
			GROUP BY cp.course.id
			""")
	List<Object[]> countCompletedGroupedByCourseIdIn(@Param("courseIds") Collection<Long> courseIds);

	long countByCourseIdAndCompletedAtIsNotNull(Long courseId);
}
