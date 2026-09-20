package sweetgirl.croissant.world.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.world.entity.WorldCharacter;

public interface WorldCharacterRepository extends JpaRepository<WorldCharacter, String> {

	/** sortOrder 는 행성 안에서만 유일하다. 행성으로 먼저 묶어야 순서가 결정적이다. */
	List<WorldCharacter> findAllByActiveTrueOrderByPlanetCodeAscSortOrderAsc();

	/** 마스코트는 부분 유니크 인덱스 덕에 많아야 하나다. */
	Optional<WorldCharacter> findByMascotTrue();
}
