package sweetgirl.croissant.monthlyreport.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.monthlyreport.entity.MonthlyReportSourceHistory;

public interface MonthlyReportSourceHistoryRepository extends JpaRepository<MonthlyReportSourceHistory, Long> {

	@Query(value = "SELECT covered_from FROM monthly_report_history_coverage WHERE id = 1", nativeQuery = true)
	LocalDateTime findCoveredFrom();

	/** 최신 버전 판정에는 다른 소유자의 버전과 삭제 표식도 포함해야 이관·삭제 전 상태가 살아나지 않는다. */
	@Query(value = """
			SELECT h.* FROM monthly_report_source_history h
			WHERE h.member_id = :memberId
			AND h.source_type IN ('BREAD_RECORD', 'SAVED_STORE', 'STORE_PLAN', 'COURSE')
			AND h.recorded_at < :until AND h.payload IS NOT NULL
			AND NOT EXISTS (SELECT 1 FROM monthly_report_source_history newer
				WHERE newer.source_type = h.source_type AND newer.source_key = h.source_key
				AND newer.recorded_at < :until
				AND (newer.recorded_at, newer.id) > (h.recorded_at, h.id))
			""", nativeQuery = true)
	List<MonthlyReportSourceHistory> findOwnerState(@Param("memberId") Long memberId,
			@Param("until") LocalDateTime until);

	/** 자식은 월말 부모 소유권으로 범위를 정한다. 현재 자식의 작성자/소유권으로 거르지 않는다. */
	@Query(value = """
			SELECT h.* FROM monthly_report_source_history h
			WHERE h.source_type = :sourceType AND h.parent_id IN (:parentIds)
			AND h.recorded_at < :until AND h.payload IS NOT NULL
			AND NOT EXISTS (SELECT 1 FROM monthly_report_source_history newer
				WHERE newer.source_type = h.source_type AND newer.source_key = h.source_key
				AND newer.recorded_at < :until
				AND (newer.recorded_at, newer.id) > (h.recorded_at, h.id))
			""", nativeQuery = true)
	List<MonthlyReportSourceHistory> findChildState(@Param("sourceType") String sourceType,
			@Param("parentIds") Set<Long> parentIds, @Param("until") LocalDateTime until);
}
