package sweetgirl.croissant.contribution.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportStatus;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;

public interface ContributionReportRepository extends JpaRepository<ContributionReport, Long> {

	long countByStatus(ContributionReportStatus status);

	Page<ContributionReport> findByTargetTypeAndReportType(
			ContributionTargetType targetType,
			ContributionReportType reportType,
			Pageable pageable);

	Page<ContributionReport> findByTargetTypeAndReportTypeAndStatus(
			ContributionTargetType targetType,
			ContributionReportType reportType,
			ContributionReportStatus status,
			Pageable pageable);
}
