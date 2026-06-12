package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.NationwideHotStoreRanking;

public interface NationwideHotStoreRankingRepository extends JpaRepository<NationwideHotStoreRanking, Long> {

	@Query("SELECT n FROM NationwideHotStoreRanking n JOIN FETCH n.store "
		+ "WHERE n.nationwideFamousMenu.id IN :nationwideMenuIds "
		+ "ORDER BY n.nationwideFamousMenu.id, n.rank")
	List<NationwideHotStoreRanking> findByNationwideFamousMenuIdInWithStore(
			@Param("nationwideMenuIds") List<Long> nationwideMenuIds);

	@Query("SELECT n FROM NationwideHotStoreRanking n WHERE n.store.id = :storeId")
	List<NationwideHotStoreRanking> findByStoreId(@Param("storeId") Long storeId);

	@Query("SELECT n FROM NationwideHotStoreRanking n JOIN FETCH n.store WHERE n.store.id IN :storeIds")
	List<NationwideHotStoreRanking> findByStoreIdIn(@Param("storeIds") List<Long> storeIds);
}
