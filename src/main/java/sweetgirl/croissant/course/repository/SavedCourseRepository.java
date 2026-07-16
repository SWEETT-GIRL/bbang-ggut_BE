package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
