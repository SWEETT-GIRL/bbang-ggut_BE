package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.store.entity.NaverEnrichCandidate;

public interface NaverEnrichCandidateRepository extends JpaRepository<NaverEnrichCandidate, Long> {

	List<NaverEnrichCandidate> findByStoreIdOrderByDisplayOrderAsc(Long storeId);

	@Modifying
	@Query("DELETE FROM NaverEnrichCandidate c WHERE c.storeId = :storeId")
	void deleteByStoreId(Long storeId);
}
