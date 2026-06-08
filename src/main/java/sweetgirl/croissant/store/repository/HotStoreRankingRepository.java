package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.HotStoreRanking;

public interface HotStoreRankingRepository extends JpaRepository<HotStoreRanking, Long> {

	@Query("SELECT h FROM HotStoreRanking h JOIN FETCH h.store "
		+ "WHERE h.famousMenuByRegion.id IN :famousMenuIds "
		+ "ORDER BY h.famousMenuByRegion.id, h.rank")
	List<HotStoreRanking> findByFamousMenuByRegionIdInWithStore(@Param("famousMenuIds") List<Long> famousMenuIds);
}
