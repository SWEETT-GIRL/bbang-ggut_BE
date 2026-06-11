package sweetgirl.croissant.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.SearchRegionAlias;

public interface SearchRegionAliasRepository extends JpaRepository<SearchRegionAlias, Long> {

	Optional<SearchRegionAlias> findByNormalizedAlias(String normalizedAlias);

	List<SearchRegionAlias> findByRegionId(Long regionId);
}
