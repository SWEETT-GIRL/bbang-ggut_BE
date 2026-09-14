package sweetgirl.croissant.recommendation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.recommendation.entity.CourseArea;

public interface CourseAreaRepository extends JpaRepository<CourseArea, Long> {

	List<CourseArea> findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(String sido);

	Optional<CourseArea> findByIdAndIsActiveTrue(Long id);

	/** 같은 도장 칸의 동네들(대표 행이 먼저). 동네가 하나뿐인 칸은 한 행이다. */
	List<CourseArea> findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc(String sido,
			String passportSpot);
}
