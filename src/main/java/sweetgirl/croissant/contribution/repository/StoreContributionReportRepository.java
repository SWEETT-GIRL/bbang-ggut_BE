package sweetgirl.croissant.contribution.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.store.enums.StoreReportStatus;

public interface StoreContributionReportRepository extends JpaRepository<StoreContributionReport, Long> {

	Optional<StoreContributionReport> findByContributionReportId(Long contributionReportId);

	List<StoreContributionReport> findByContributionReportIdIn(List<Long> contributionReportIds);

	boolean existsByNaverPlaceIdAndStatus(String naverPlaceId, StoreReportStatus status);

	@Query("""
				SELECT COUNT(r) > 0 FROM StoreContributionReport r
				WHERE r.name = :name
					AND r.address = :address
					AND r.status = :status
					AND r.requestType = 'STORE_ADD'
			""")
	boolean existsPendingByNameAndAddress(
			@Param("name") String name,
			@Param("address") String address,
			@Param("status") StoreReportStatus status);
}
