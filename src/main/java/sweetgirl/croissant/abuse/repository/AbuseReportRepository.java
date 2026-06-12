package sweetgirl.croissant.abuse.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.abuse.dto.projection.AbuseReportMemberCountProjection;
import sweetgirl.croissant.abuse.dto.projection.AbuseReportTargetCountProjection;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;

public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {

	boolean existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			Long reporterId,
			AbuseReportTargetType targetType,
			Long targetId,
			List<AbuseReportStatus> statuses);

	@Override
	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	Page<AbuseReport> findAll(Pageable pageable);

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	Page<AbuseReport> findByStatus(AbuseReportStatus status, Pageable pageable);

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	List<AbuseReport> findByStatus(AbuseReportStatus status);

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	@Query("SELECT r FROM AbuseReport r")
	List<AbuseReport> findAllWithMembers();

	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	@Query("SELECT r FROM AbuseReport r WHERE r.id = :reportId")
	Optional<AbuseReport> findByIdWithMembers(@Param("reportId") Long reportId);

	long countByStatus(AbuseReportStatus status);

	long countByCreatedAtGreaterThanEqual(LocalDateTime since);

	@Query("""
				SELECT r.targetType AS targetType,
					r.targetId AS targetId,
					COUNT(DISTINCT r.reporter.id) AS reporterCount
				FROM AbuseReport r
				WHERE r.targetType = :targetType
					AND r.createdAt >= :since
					AND (:status IS NULL OR r.status = :status)
				GROUP BY r.targetType, r.targetId
			""")
	List<AbuseReportTargetCountProjection> findTargetReporterCountsSince(
			@Param("targetType") AbuseReportTargetType targetType,
			@Param("since") LocalDateTime since,
			@Param("status") AbuseReportStatus status);

	@Query("""
				SELECT r.targetType AS targetType,
					r.targetId AS targetId,
					COUNT(DISTINCT r.reporter.id) AS reporterCount
				FROM AbuseReport r
				WHERE r.reasonType IN :reasonTypes
					AND (:status IS NULL OR r.status = :status)
				GROUP BY r.targetType, r.targetId
			""")
	List<AbuseReportTargetCountProjection> findSensitiveReasonReporterCounts(
			@Param("reasonTypes") List<AbuseReportReasonType> reasonTypes,
			@Param("status") AbuseReportStatus status);

	@Query("""
				SELECT r.reportedMember.id AS reportedMemberId,
					COUNT(DISTINCT r.targetId) AS reviewTargetCount
				FROM AbuseReport r
				WHERE r.targetType = :targetType
					AND r.createdAt >= :since
					AND (:status IS NULL OR r.status = :status)
				GROUP BY r.reportedMember.id
			""")
	List<AbuseReportMemberCountProjection> findMemberReviewTargetCountsSince(
			@Param("targetType") AbuseReportTargetType targetType,
			@Param("since") LocalDateTime since,
			@Param("status") AbuseReportStatus status);
}
