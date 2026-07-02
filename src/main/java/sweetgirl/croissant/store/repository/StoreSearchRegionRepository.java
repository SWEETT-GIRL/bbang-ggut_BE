package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.dto.projection.StoreSearchRegionAliasProjection;
import sweetgirl.croissant.store.dto.projection.StoreSearchRegionIndexProjection;
import sweetgirl.croissant.store.entity.StoreSearchRegion;

public interface StoreSearchRegionRepository extends JpaRepository<StoreSearchRegion, Long> {

	List<StoreSearchRegion> findByStoreIdOrderByWeightDesc(Long storeId);

	List<StoreSearchRegion> findByRegionIdOrderByWeightDesc(Long regionId);

	boolean existsByStoreIdAndRegionId(Long storeId, Long regionId);

	@Query("""
			SELECT
				region.id AS regionId,
				region.name AS regionName,
				region.type AS regionType,
				storeRegion.weight AS weight
			FROM StoreSearchRegion storeRegion
			JOIN storeRegion.region region
			WHERE storeRegion.store.id = :storeId
				AND region.isActive = TRUE
			ORDER BY storeRegion.isPrimary DESC, storeRegion.weight DESC, region.displayOrder ASC, region.name ASC
			""")
	List<StoreSearchRegionIndexProjection> findSearchRegionsForIndexingByStoreId(@Param("storeId") Long storeId);

	@Query("""
			SELECT DISTINCT alias.alias
			FROM StoreSearchRegion storeRegion
			JOIN storeRegion.region region
			JOIN SearchRegionAlias alias ON alias.region = region
			WHERE storeRegion.store.id = :storeId
				AND region.isActive = TRUE
			ORDER BY alias.alias ASC
			""")
	List<String> findSearchRegionAliasesForIndexingByStoreId(@Param("storeId") Long storeId);

	@Query("""
			SELECT
				storeRegion.store.id AS storeId,
				region.id AS regionId,
				region.name AS regionName,
				region.type AS regionType,
				storeRegion.weight AS weight
			FROM StoreSearchRegion storeRegion
			JOIN storeRegion.region region
			WHERE storeRegion.store.id IN :storeIds
				AND region.isActive = TRUE
			ORDER BY storeRegion.store.id ASC, storeRegion.isPrimary DESC, storeRegion.weight DESC,
				region.displayOrder ASC, region.name ASC
			""")
	List<StoreSearchRegionIndexProjection> findSearchRegionsForIndexingByStoreIds(
			@Param("storeIds") List<Long> storeIds);

	@Query("""
			SELECT DISTINCT storeRegion.store.id AS storeId, alias.alias AS alias
			FROM StoreSearchRegion storeRegion
			JOIN storeRegion.region region
			JOIN SearchRegionAlias alias ON alias.region = region
			WHERE storeRegion.store.id IN :storeIds
				AND region.isActive = TRUE
			ORDER BY storeRegion.store.id ASC, alias.alias ASC
			""")
	List<StoreSearchRegionAliasProjection> findSearchRegionAliasesForIndexingByStoreIds(
			@Param("storeIds") List<Long> storeIds);
}
