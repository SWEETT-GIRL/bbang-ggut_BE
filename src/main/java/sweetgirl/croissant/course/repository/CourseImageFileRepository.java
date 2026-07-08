package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.CourseImageFile;

public interface CourseImageFileRepository extends JpaRepository<CourseImageFile, Long> {

	Optional<CourseImageFile> findByCourseId(Long courseId);

	List<CourseImageFile> findAllByCourseIdIn(List<Long> courseIds);

	@Query(value = "SELECT * FROM course_image_files WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<CourseImageFile> findByAwsKey(@Param("awsKey") String awsKey);
}
