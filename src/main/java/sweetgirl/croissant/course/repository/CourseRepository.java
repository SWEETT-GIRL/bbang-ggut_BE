package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseType;

public interface CourseRepository extends JpaRepository<Course, Long> {

	List<Course> findAllByIsPublicTrueAndDeletedAtIsNull();

	List<Course> findAllByMemberIdAndTypeInAndDeletedAtIsNull(Long memberId, List<CourseType> types);

	Optional<Course> findByIdAndDeletedAtIsNull(Long id);

	List<Course> findAllByTypeAndDeletedAtIsNullOrderByCreatedAtDesc(CourseType type);

	long countByPinOrderIsNotNullAndDeletedAtIsNull();

	Optional<Course> findByPinOrderAndDeletedAtIsNull(Integer pinOrder);
}
