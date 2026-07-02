package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.course.entity.SavedCourse;

public interface SavedCourseRepository extends JpaRepository<SavedCourse, Long> {

	List<SavedCourse> findAllByMemberId(Long memberId);

	boolean existsByMemberIdAndCourseId(Long memberId, Long courseId);

	Optional<SavedCourse> findByMemberIdAndCourseId(Long memberId, Long courseId);

	long countByCourseId(Long courseId);
}
