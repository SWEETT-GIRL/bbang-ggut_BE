package sweetgirl.croissant.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.report.dto.projection.UserReportMemberCountProjection;
import sweetgirl.croissant.report.dto.projection.UserReportTargetCountProjection;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ReportReasonType;
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

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	List<UserReport> findByStatus(UserReportStatus status);

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	@Query("SELECT r FROM UserReport r")
	List<UserReport> findAllWithMembers();

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	@Query("SELECT r FROM UserReport r WHERE r.id = :reportId")
	Optional<UserReport> findByIdWithMembers(@Param("reportId") Long reportId);

	long countByStatus(UserReportStatus status);

	long countByCreatedAtGreaterThanEqual(LocalDateTime since);

	@Query("""
				SELECT r.targetType AS targetType,
					r.targetId AS targetId,
					COUNT(DISTINCT r.reporter.id) AS reporterCount
				FROM UserReport r
				WHERE r.targetType = :targetType
					AND r.createdAt >= :since
					AND (:status IS NULL OR r.status = :status)
				GROUP BY r.targetType, r.targetId
			""")
	List<UserReportTargetCountProjection> findTargetReporterCountsSince(
			@Param("targetType") ReportTargetType targetType,
			@Param("since") LocalDateTime since,
			@Param("status") UserReportStatus status);

	@Query("""
				SELECT r.targetType AS targetType,
					r.targetId AS targetId,
					COUNT(DISTINCT r.reporter.id) AS reporterCount
				FROM UserReport r
				WHERE r.reasonType IN :reasonTypes
					AND (:status IS NULL OR r.status = :status)
				GROUP BY r.targetType, r.targetId
			""")
	List<UserReportTargetCountProjection> findSensitiveReasonReporterCounts(
			@Param("reasonTypes") List<ReportReasonType> reasonTypes,
			@Param("status") UserReportStatus status);

	@Query("""
				SELECT r.reportedMember.id AS reportedMemberId,
					COUNT(DISTINCT r.targetId) AS reviewTargetCount
				FROM UserReport r
				WHERE r.targetType = :targetType
					AND r.createdAt >= :since
					AND (:status IS NULL OR r.status = :status)
				GROUP BY r.reportedMember.id
			""")
	List<UserReportMemberCountProjection> findMemberReviewTargetCountsSince(
			@Param("targetType") ReportTargetType targetType,
			@Param("since") LocalDateTime since,
			@Param("status") UserReportStatus status);
}
