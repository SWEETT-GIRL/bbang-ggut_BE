package sweetgirl.croissant.monthlyreport.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.croissant.monthlyreport.entity.MonthlyReportJob;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;

public interface MonthlyReportJobRepository extends JpaRepository<MonthlyReportJob, MonthlyReportMemberId> {
	@Transactional
	@Modifying
	@Query(value = """
			INSERT INTO monthly_report_job (member_id, year_month, status, draft, raw_metrics,
				calculation_version, attempts, next_attempt_at, created_at)
			VALUES (:memberId, :yearMonth, 'READY', CAST(:draft AS jsonb), CAST(:metrics AS jsonb),
					:version, 0, :now, :now)
			ON CONFLICT (member_id, year_month) DO NOTHING
			""", nativeQuery = true)
	int enqueue(@Param("memberId") Long memberId, @Param("yearMonth") String yearMonth,
			@Param("draft") String draft, @Param("metrics") String metrics, @Param("version") String version,
			@Param("now") Instant now);

	@Query(value = """
			SELECT * FROM monthly_report_job WHERE member_id = :memberId AND year_month = :yearMonth
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<MonthlyReportJob> lockJob(@Param("memberId") Long memberId, @Param("yearMonth") String yearMonth);

	@Query("""
			SELECT DISTINCT j.id.yearMonth FROM MonthlyReportJob j
			WHERE j.status IN (sweetgirl.croissant.monthlyreport.enums.MonthlyReportJobStatus.READY,
				sweetgirl.croissant.monthlyreport.enums.MonthlyReportJobStatus.FAILED)
				AND j.nextAttemptAt <= :now AND j.attempts < :maxAttempts
			ORDER BY j.id.yearMonth
			""")
	List<String> findDueMonths(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

	@Query("""
			SELECT j.id.memberId FROM MonthlyReportJob j
			WHERE j.id.yearMonth = :yearMonth AND j.id.memberId > :afterId
				AND j.status IN (sweetgirl.croissant.monthlyreport.enums.MonthlyReportJobStatus.READY,
					sweetgirl.croissant.monthlyreport.enums.MonthlyReportJobStatus.FAILED)
				AND j.nextAttemptAt <= :now AND j.attempts < :maxAttempts
			ORDER BY j.id.memberId
			""")
	Slice<Long> findDueMembers(@Param("yearMonth") String yearMonth, @Param("afterId") long afterId,
			@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Pageable pageable);
}
