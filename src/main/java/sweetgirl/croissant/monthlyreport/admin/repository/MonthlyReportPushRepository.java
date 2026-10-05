package sweetgirl.croissant.monthlyreport.admin.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportPush;

public interface MonthlyReportPushRepository extends JpaRepository<MonthlyReportPush, Long> {
	@Query("""
			select p.notificationId from MonthlyReportPush p
			where p.done = false and (p.attempts < 5 or p.leaseToken is not null)
			and p.nextAttemptAt <= :now and (p.leaseExpiresAt is null or p.leaseExpiresAt <= :now)
			order by p.nextAttemptAt
			""")
	List<Long> findDue(@Param("now") Instant now, Pageable pageable);

	@Query(value = """
			SELECT * FROM monthly_report_push WHERE notification_id = :id FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<MonthlyReportPush> lock(@Param("id") Long id);
}
