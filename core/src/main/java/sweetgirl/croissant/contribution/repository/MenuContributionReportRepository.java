package sweetgirl.croissant.contribution.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.contribution.entity.MenuContributionReport;

public interface MenuContributionReportRepository extends JpaRepository<MenuContributionReport, Long> {

	Optional<MenuContributionReport> findByContributionReportId(Long contributionReportId);

	List<MenuContributionReport> findByContributionReportIdIn(List<Long> contributionReportIds);
}
