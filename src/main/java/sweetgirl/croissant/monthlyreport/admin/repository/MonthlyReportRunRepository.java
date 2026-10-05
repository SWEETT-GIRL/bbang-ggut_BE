package sweetgirl.croissant.monthlyreport.admin.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;

public interface MonthlyReportRunRepository extends JpaRepository<MonthlyReportRun, UUID> {
	Optional<MonthlyReportRun> findByYearMonth(String month);

	@Query("select r.id from MonthlyReportRun r where r.state in :states order by r.createdAt")
	List<UUID> findActive(@Param("states") Collection<MonthlyReportRun.State> states, Pageable pageable);

	@Modifying
	@Query(value = """
			INSERT INTO monthly_report_run (id, year_month, asset_version, state, created_at) VALUES (:id,
			:month, :version, 'PREPARING', :now) ON CONFLICT (year_month) DO NOTHING
			""", nativeQuery = true)
	int enqueue(@Param("id") UUID id, @Param("month") String month, @Param("version") String version,
			@Param("now") Instant now);

	@Query(value = "SELECT * FROM monthly_report_run WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
	Optional<MonthlyReportRun> lock(@Param("id") UUID id);
}
