package sweetgirl.croissant.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.SearchRegion;
import sweetgirl.croissant.store.enums.SearchRegionLevel;
import sweetgirl.croissant.store.enums.SearchRegionType;

public interface SearchRegionRepository extends JpaRepository<SearchRegion, Long> {

	Optional<SearchRegion> findByNameAndType(String name, SearchRegionType type);

	List<SearchRegion> findByTypeAndIsActiveTrueOrderByDisplayOrderAscNameAsc(SearchRegionType type);

	List<SearchRegion> findByLevelAndIsActiveTrueOrderByDisplayOrderAscNameAsc(SearchRegionLevel level);

	List<SearchRegion> findByParentRegionIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(Long parentRegionId);
}
