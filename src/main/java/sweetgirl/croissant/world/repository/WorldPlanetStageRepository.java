package sweetgirl.croissant.world.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.world.entity.WorldPlanetStage;

public interface WorldPlanetStageRepository extends JpaRepository<WorldPlanetStage, Long> {

	List<WorldPlanetStage> findAllByOrderByPlanetCodeAscStageOrderAsc();

	List<WorldPlanetStage> findAllByPlanetCodeOrderByStageOrderAsc(String planetCode);
}
