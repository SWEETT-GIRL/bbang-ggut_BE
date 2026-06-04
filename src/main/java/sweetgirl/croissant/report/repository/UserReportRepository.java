package sweetgirl.croissant.report.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.enums.UserReportStatus;

public interface UserReportRepository extends JpaRepository<UserReport, Long> {

	boolean existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			Long reporterId,
			ReportTargetType targetType,
			Long targetId,
			List<UserReportStatus> statuses);

	@Override
	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	Page<UserReport> findAll(Pageable pageable);

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	Page<UserReport> findByStatus(UserReportStatus status, Pageable pageable);
}
