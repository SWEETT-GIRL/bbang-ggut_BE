package sweetgirl.croissant.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.CourseArea;

public interface CourseAreaRepository extends JpaRepository<CourseArea, Long> {

	List<CourseArea> findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(String sido);
}
