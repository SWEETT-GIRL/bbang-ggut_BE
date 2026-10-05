package sweetgirl.croissant.monthlyreport.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportJobStatus;

/** 발행 스냅샷과 분리된 실행 상태. 수정은 작업 행 잠금을 얻은 트랜잭션에서만 수행한다. */
@Entity
@Table(name = "monthly_report_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportJob {
	@EmbeddedId
	private MonthlyReportMemberId id;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private MonthlyReportJobStatus status;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String draft;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_metrics", nullable = false, columnDefinition = "jsonb")
	private String rawMetrics;
	@Column(name = "calculation_version", nullable = false, length = 100)
	private String calculationVersion;
	@Column(nullable = false)
	private int attempts;
	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;
	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;
	@Column(name = "last_error_code", length = 100)
	private String lastErrorCode;
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public boolean canRun(Instant now, int maxAttempts) {
		return (status == MonthlyReportJobStatus.READY || status == MonthlyReportJobStatus.FAILED)
			&& attempts < maxAttempts && !nextAttemptAt.isAfter(now);
	}

	public void begin(Instant now) {
		attempts++;
		lastAttemptAt = now;
	}

	public void complete() {
		status = MonthlyReportJobStatus.COMPLETED;
		lastErrorCode = null;
	}

	public void cancel() {
		status = MonthlyReportJobStatus.CANCELLED;
		lastErrorCode = null;
	}

	public void fail(String errorCode, Instant nextAttempt) {
		status = MonthlyReportJobStatus.FAILED;
		lastErrorCode = errorCode;
		nextAttemptAt = nextAttempt;
	}
}
