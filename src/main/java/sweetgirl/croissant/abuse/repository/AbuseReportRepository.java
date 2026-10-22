package sweetgirl.croissant.abuse.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	/** 같은 대상을 신고한 서로 다른 사람 수 — 3명이면 자동 숨김. */
	@Query("""
			SELECT COUNT(DISTINCT r.reporter.id) FROM AbuseReport r
			WHERE r.targetType = :targetType AND r.targetId = :targetId AND r.status IN :statuses
			""")
	long countDistinctReporters(@Param("targetType") AbuseReportTargetType targetType,
			@Param("targetId") Long targetId, @Param("statuses") List<AbuseReportStatus> statuses);

	/** 이 사람이 신고한(처리 전 · 받아들여진) 대상 id — 신고자 화면에서 바로 숨기려고. */
	@Query("""
			SELECT r.targetId FROM AbuseReport r
			WHERE r.reporter.id = :reporterId AND r.targetType = :targetType AND r.status IN :statuses
			""")
	List<Long> findTargetIdsByReporter(@Param("reporterId") Long reporterId,
			@Param("targetType") AbuseReportTargetType targetType, @Param("statuses") List<AbuseReportStatus> statuses);

	/** 24시간 넘게 처리 안 된 신고 중 아직 재알림 안 한 것. */
	@EntityGraph(attributePaths = {"reporter", "reportedMember"})
	@Query("""
			SELECT r FROM AbuseReport r
			WHERE r.status = :status AND r.createdAt < :before AND r.remindedAt IS NULL
			ORDER BY r.createdAt ASC
			""")
	List<AbuseReport> findOverdue(@Param("status") AbuseReportStatus status, @Param("before") LocalDateTime before);

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

	/** 탈퇴한 지 cutoff 를 넘긴 사람이 한 신고 · 받은 신고를 지운다 — 보관 기한 뒤. 조치 · 이의제기를 먼저 지운다. */
	@Modifying
	@Query("""
			DELETE FROM AbuseReport r
			WHERE r.reporter.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
				OR r.reportedMember.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
			""")
	int deleteAllOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
