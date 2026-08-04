package sweetgirl.croissant.course.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.dto.projection.CourseProgressAggregateProjection;
import sweetgirl.croissant.course.dto.projection.CourseProgressMemberAggregateProjection;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.entity.CourseProgressId;
import sweetgirl.croissant.course.enums.CourseProgressStatus;

public interface CourseProgressRepository extends JpaRepository<CourseProgress, CourseProgressId> {

	@EntityGraph(attributePaths = "course")
	List<CourseProgress> findAllByMemberId(Long memberId);

	@EntityGraph(attributePaths = "course")
	List<CourseProgress> findAllByMemberIdAndStatus(Long memberId, CourseProgressStatus status);

	@EntityGraph(attributePaths = "course")
	Optional<CourseProgress> findByMemberIdAndCourseId(Long memberId, Long courseId);

	List<CourseProgress> findAllByCourseIdAndStatus(Long courseId, CourseProgressStatus status);

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
			AND cp.status = sweetgirl.croissant.course.enums.CourseProgressStatus.FINISHED
			GROUP BY cp.course.id
			""")
	List<Object[]> countCompletedGroupedByCourseIdIn(@Param("courseIds") Collection<Long> courseIds);

	long countByCourseIdAndStatus(Long courseId, CourseProgressStatus status);

	long countByMemberIdAndStatus(Long memberId, CourseProgressStatus status);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE course_progress target
			SET started_at = LEAST(target.started_at, guest.started_at),
				status = CASE
					WHEN target.status = 'FINISHED' OR guest.status = 'FINISHED' THEN 'FINISHED'
					WHEN target.status = 'IN_PROGRESS' OR guest.status = 'IN_PROGRESS' THEN 'IN_PROGRESS'
					ELSE 'STOPPED'
				END,
				finished_at = CASE
					WHEN target.finished_at IS NULL THEN guest.finished_at
					WHEN guest.finished_at IS NULL THEN target.finished_at
					ELSE LEAST(target.finished_at, guest.finished_at)
				END,
				completed_at = CASE
					WHEN target.completed_at IS NULL THEN guest.completed_at
					WHEN guest.completed_at IS NULL THEN target.completed_at
					ELSE LEAST(target.completed_at, guest.completed_at)
				END,
				stopped_at = CASE
					WHEN target.status = 'FINISHED' OR guest.status = 'FINISHED' THEN NULL
					WHEN target.status = 'IN_PROGRESS' OR guest.status = 'IN_PROGRESS' THEN NULL
					WHEN target.stopped_at IS NULL THEN guest.stopped_at
					WHEN guest.stopped_at IS NULL THEN target.stopped_at
					ELSE GREATEST(target.stopped_at, guest.stopped_at)
				END,
				updated_at = CURRENT_TIMESTAMP
			FROM course_progress guest
			WHERE target.member_id = :targetId
			AND guest.member_id = :guestId
			AND target.course_id = guest.course_id
			""", nativeQuery = true)
	int mergeGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM course_progress guest
			WHERE guest.member_id = :guestId
			AND EXISTS (
				SELECT 1 FROM course_progress target
				WHERE target.member_id = :targetId
				AND target.course_id = guest.course_id
			)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE course_progress SET member_id = :targetId WHERE member_id = :guestId", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
