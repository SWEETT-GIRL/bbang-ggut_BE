package sweetgirl.croissant.monthlyreport.admin.entity;

import java.time.Instant;
import java.util.UUID;

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
	@Column(name = "lease_token")
	private UUID leaseToken;
	@Column(name = "lease_expires_at")
	private Instant leaseExpiresAt;

	public boolean isClaimAvailable(Instant now) {
		return !done && !nextAttemptAt.isAfter(now)
			&& (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now));
	}

	public UUID claimDelivery(Instant now) {
		begin();
		leaseToken = UUID.randomUUID();
		leaseExpiresAt = now.plusSeconds(600);
		return leaseToken;
	}

	public boolean ownsDelivery(UUID token) {
		return token != null && token.equals(leaseToken);
	}

	private void releaseDelivery() {
		leaseToken = null;
		leaseExpiresAt = null;
	}

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
		releaseDelivery();
		done = true;
		completedAt = Instant.now();
		errorCode = null;
	}

	public void skip(String reason) {
		releaseDelivery();
		done = true;
		completedAt = Instant.now();
		errorCode = reason;
	}

	public boolean retry() {
		if (done || attempts < 5 || (leaseExpiresAt != null && leaseExpiresAt.isAfter(Instant.now()))) {
			return false;
		}
		releaseDelivery();
		attempts = 0;
		manualRetries++;
		done = false;
		errorCode = null;
		nextAttemptAt = Instant.now();
		return true;
	}

	public void fail() {
		releaseDelivery();
		errorCode = "PUSH_FAILED";
		nextAttemptAt = Instant.now().plusSeconds(300L * attempts);
	}
}
