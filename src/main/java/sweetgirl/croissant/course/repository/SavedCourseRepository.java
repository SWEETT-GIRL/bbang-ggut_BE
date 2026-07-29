package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.SavedCourse;

public interface SavedCourseRepository extends JpaRepository<SavedCourse, Long> {

	List<SavedCourse> findAllByMemberId(Long memberId);

	boolean existsByMemberIdAndCourseId(Long memberId, Long courseId);

	Optional<SavedCourse> findByMemberIdAndCourseId(Long memberId, Long courseId);

	long countByCourseId(Long courseId);

	@Query("SELECT sc.course.id, COUNT(sc) FROM SavedCourse sc"
		+ " WHERE sc.course.id IN :courseIds GROUP BY sc.course.id")
	List<Object[]> countGroupedByCourseIdIn(@Param("courseIds") List<Long> courseIds);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	// (member_id, course_id) 유니크라 이미 저장한 코스는 게스트 쪽을 버리고 나머지만 옮긴다.
	@Modifying(clearAutomatically = true)
	@Query("DELETE FROM SavedCourse s WHERE s.member.id = :guestId AND EXISTS "
		+ "(SELECT 1 FROM SavedCourse t WHERE t.member.id = :targetId AND t.course.id = s.course.id)")
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE SavedCourse s SET s.member.id = :targetId WHERE s.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

}
