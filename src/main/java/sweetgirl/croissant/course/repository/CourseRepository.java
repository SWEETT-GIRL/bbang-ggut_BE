package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseType;

public interface CourseRepository extends JpaRepository<Course, Long> {

	@Query("""
			SELECT course
			FROM Course course
			LEFT JOIN SavedCourse savedCourse ON savedCourse.course = course
			WHERE course.isPublic = true
			AND course.deletedAt IS NULL
			GROUP BY course
			ORDER BY
				CASE WHEN course.pinOrder IS NULL THEN 1 ELSE 0 END ASC,
				course.pinOrder ASC,
				COUNT(savedCourse.id) DESC,
				course.id DESC
			""")
	List<Course> findAllPublicOrderedByPinAndSavedCount();

	List<Course> findAllByMemberIdAndTypeInAndDeletedAtIsNull(Long memberId, List<CourseType> types);

	long countByMemberIdAndTypeInAndDeletedAtIsNull(Long memberId, List<CourseType> types);

	Optional<Course> findByIdAndDeletedAtIsNull(Long id);

	List<Course> findAllByTypeAndDeletedAtIsNullOrderByCreatedAtDesc(CourseType type);

	long countByPinOrderIsNotNullAndDeletedAtIsNull();

	Optional<Course> findByPinOrderAndDeletedAtIsNull(Integer pinOrder);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	// 코스는 유니크 제약이 없어 그대로 주인만 바꾼다.
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Course c SET c.member.id = :targetId WHERE c.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

}
