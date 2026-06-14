package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.dto.projection.HotStoreRankingTrendProjection;
import sweetgirl.croissant.store.entity.HotStoreRanking;

public interface HotStoreRankingRepository extends JpaRepository<HotStoreRanking, Long> {

	@Query("SELECT h.famousMenuByRegion.id AS famousMenuByRegionId, h.rank AS rank, "
		+ "h.store.id AS storeId, h.store.name AS storeName, h.store.address AS storeAddress "
		+ "FROM HotStoreRanking h "
		+ "WHERE h.famousMenuByRegion.id IN :famousMenuIds "
		+ "ORDER BY h.famousMenuByRegion.id, h.rank")
	List<HotStoreRankingTrendProjection> findTrendByFamousMenuByRegionIdIn(
			@Param("famousMenuIds") List<Long> famousMenuIds);
}
