package sweetgirl.croissant.world.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.world.entity.WorldCharacter;

public interface WorldCharacterRepository extends JpaRepository<WorldCharacter, String> {

	/** sortOrder 는 행성 안에서만 유일하다. 행성으로 먼저 묶어야 순서가 결정적이다. */
	List<WorldCharacter> findAllByActiveTrueOrderByPlanetCodeAscSortOrderAsc();

	/** 마스코트는 부분 유니크 인덱스 덕에 많아야 하나다. */
	Optional<WorldCharacter> findByMascotTrue();

	List<WorldCharacter> findAllByPlanetCode(String planetCode);

	/**
	 * 지금 마스코트를 내린다. 새 마스코트를 세우기 <b>전에</b> 부른다 — 부분 유니크 인덱스가
	 * 둘을 못 버티는데, 엔티티만 고치면 flush 순서에 따라 둘이 공존하는 찰나가 생겨 저장이 깨진다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE world_characters SET mascot = FALSE, updated_at = CURRENT_TIMESTAMP
			WHERE mascot = TRUE
			""", nativeQuery = true)
	int clearMascot();
}
