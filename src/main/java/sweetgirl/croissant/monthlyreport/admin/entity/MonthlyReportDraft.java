package sweetgirl.croissant.monthlyreport.admin.entity;

import java.time.Instant;
import java.util.UUID;

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

@Entity
@Table(name = "monthly_report_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportDraft {
	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@EmbeddedId
	private MonthlyReportDraftId id;
	@Column(name = "run_id", nullable = false, insertable = false, updatable = false)
	private UUID runId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private State state;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String payload;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_metrics", columnDefinition = "jsonb")
	private String rawMetrics;
	@Column(name = "generated_at")
	private Instant generatedAt;
	private int attempts;
	@Column(name = "error_code", length = 100)
	private String errorCode;

	public enum State {
		PENDING, PREPARED, FAILED, PUBLISHED, SKIPPED, PUBLISH_FAILED
	}

	public static MonthlyReportDraft create(UUID runId, Long memberId, String month) {
		MonthlyReportDraft draft = new MonthlyReportDraft();
		draft.id = new MonthlyReportDraftId(runId, memberId, month);
		draft.runId = runId;
		draft.state = State.PENDING;
		return draft;
	}

	public void prepare(String json, String metrics, Instant at) {
		payload = json;
		rawMetrics = metrics;
		generatedAt = at;
		state = State.PREPARED;
		errorCode = null;
	}

	public void begin() {
		attempts++;
	}

	public void fail(String error, boolean publishing) {
		errorCode = error;
		state = publishing ? State.PUBLISH_FAILED : State.FAILED;
	}

	public void finish(boolean published) {
		state = published ? State.PUBLISHED : State.SKIPPED;
		errorCode = null;
	}

	public void retry() {
		state = state == State.PUBLISH_FAILED ? State.PREPARED : State.PENDING;
		errorCode = null;
	}
}
