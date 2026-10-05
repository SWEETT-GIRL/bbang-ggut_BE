package sweetgirl.croissant.monthlyreport.admin.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "monthly_report_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportRun {
	@Id
	private UUID id;
	@Column(name = "year_month", nullable = false, length = 7)
	private String yearMonth;
	@Column(name = "asset_version", nullable = false, length = 60)
	private String assetVersion;
	@Column(name = "target_member_id")
	private Long targetMemberId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private State state;
	@Column(name = "after_member_id")
	private long afterMemberId;
	@Column(name = "scan_complete")
	private boolean scanComplete;
	@Column(name = "sources_ready")
	private boolean sourcesReady;
	@Column(name = "push_at")
	private Instant pushAt;
	@Column(name = "created_at")
	private Instant createdAt;
	@Column(name = "error_code", length = 100)
	private String errorCode;

	public enum State {
		PREPARING, REVIEW, PUBLISHING, COMPLETE, BLOCKED
	}

	public static MonthlyReportRun create(String month, String version) {
		MonthlyReportRun run = new MonthlyReportRun();
		run.id = UUID.randomUUID();
		run.yearMonth = month;
		run.assetVersion = version;
		run.state = State.PREPARING;
		run.createdAt = Instant.now();
		return run;
	}

	public void sourceReady() {
		sourcesReady = true;
		errorCode = null;
	}

	public void advance(long memberId) {
		afterMemberId = memberId;
	}

	public void reviewed() {
		scanComplete = true;
		state = State.REVIEW;
	}

	public void approve(Instant at) {
		pushAt = at;
		state = State.PUBLISHING;
	}

	public void complete() {
		state = State.COMPLETE;
	}

	public void block(String error) {
		errorCode = error;
		state = State.BLOCKED;
	}

	public void retry(boolean publishing) {
		errorCode = null;
		state = publishing ? State.PUBLISHING : State.PREPARING;
	}
}
