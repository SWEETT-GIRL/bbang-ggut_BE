package sweetgirl.croissant.world.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.world.entity.WorldCatalogVersion;

public interface WorldCatalogVersionRepository extends JpaRepository<WorldCatalogVersion, Short> {

	/**
	 * 버전 숫자 하나만 읽는다. 카탈로그 캐시가 아직 쓸 만한지 판단하는 데 쓰이므로
	 * 요청마다 불리고, 그래서 행 전체가 아니라 칸 하나만 가져온다.
	 */
	@Query("SELECT catalog.version FROM WorldCatalogVersion catalog WHERE catalog.id = 1")
	Optional<Integer> findCurrentVersion();
}
