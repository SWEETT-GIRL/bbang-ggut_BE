package sweetgirl.croissant.contribution.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportStatus;

public interface ContributionReportRepository extends JpaRepository<ContributionReport, Long> {

	long countByStatus(ContributionReportStatus status);
}
