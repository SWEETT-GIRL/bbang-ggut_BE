package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.store.entity.StoreSearchRegion;

public interface StoreSearchRegionRepository extends JpaRepository<StoreSearchRegion, Long> {

	List<StoreSearchRegion> findByStoreIdOrderByWeightDesc(Long storeId);

	List<StoreSearchRegion> findByRegionIdOrderByWeightDesc(Long regionId);

	boolean existsByStoreIdAndRegionId(Long storeId, Long regionId);
}
