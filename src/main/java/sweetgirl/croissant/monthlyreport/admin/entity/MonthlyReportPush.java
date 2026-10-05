package sweetgirl.croissant.monthlyreport.admin.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "monthly_report_push")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportPush {
	@Id
	@Column(name = "notification_id")
	private Long notificationId;
	@Column(name = "member_id", nullable = false)
	private Long memberId;
	@Column(name = "send_at", nullable = false)
	private Instant sendAt;
	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;
	private int attempts;
	private boolean done;
	@Column(name = "completed_at")
	private Instant completedAt;
	@Column(name = "total_attempts")
	private int totalAttempts;
	@Column(name = "manual_retries")
	private int manualRetries;

	@Column(name = "error_code", length = 100)
	private String errorCode;

	public static MonthlyReportPush schedule(Long notificationId, Long memberId, Instant sendAt) {
		MonthlyReportPush push = new MonthlyReportPush();
		push.notificationId = notificationId;
		push.memberId = memberId;
		push.sendAt = sendAt;
		push.nextAttemptAt = sendAt;
		return push;
	}

	public void begin() {
		attempts++;
		totalAttempts++;
	}

	public void complete() {
		done = true;
		completedAt = Instant.now();
		errorCode = null;
	}

	public void skip(String reason) {
		done = true;
		completedAt = Instant.now();
		errorCode = reason;
	}

	public boolean retry() {
		if (done || attempts < 5) {
			return false;
		}
		attempts = 0;
		manualRetries++;
		done = false;
		errorCode = null;
		nextAttemptAt = Instant.now();
		return true;
	}

	public void fail() {
		errorCode = "PUSH_FAILED";
		nextAttemptAt = Instant.now().plusSeconds(300L * attempts);
	}
}
