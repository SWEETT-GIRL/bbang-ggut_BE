package sweetgirl.croissant.contribution.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.contribution.entity.StoreContributionReport;

public interface StoreContributionReportRepository extends JpaRepository<StoreContributionReport, Long> {

	Optional<StoreContributionReport> findByContributionReportId(Long contributionReportId);

	List<StoreContributionReport> findByContributionReportIdIn(List<Long> contributionReportIds);
}
