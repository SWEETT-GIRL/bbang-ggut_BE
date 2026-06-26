package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.SearchRegionAlias;

public interface SearchRegionAliasRepository extends JpaRepository<SearchRegionAlias, Long> {

	List<SearchRegionAlias> findByNormalizedAlias(String normalizedAlias);

	List<SearchRegionAlias> findByRegionId(Long regionId);
}
