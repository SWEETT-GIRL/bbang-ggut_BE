package sweetgirl.croissant.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.course.entity.CourseImageFile;

public interface CourseImageFileRepository extends JpaRepository<CourseImageFile, Long> {

	Optional<CourseImageFile> findByCourseId(Long courseId);
}
