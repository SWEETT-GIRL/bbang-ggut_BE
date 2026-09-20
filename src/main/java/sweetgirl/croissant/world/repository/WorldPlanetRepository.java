package sweetgirl.croissant.world.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.world.entity.WorldPlanet;

public interface WorldPlanetRepository extends JpaRepository<WorldPlanet, String> {

	/** 갈래가 둘 이상이면 sortOrder 만으로는 동점이 생긴다. 순서를 DB 에 맡기지 않는다. */
	List<WorldPlanet> findAllByActiveTrueOrderBySortOrderAscCodeAsc();
}
