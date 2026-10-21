package sweetgirl.dataops.signal.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가게 신호(태그 소스) — StoreSignalParser 로 파싱한 결과. 가게당 1행, 갱신으로 덮어쓴다.
 * 스칼라 컬럼은 조회·집계용 요약, 나머지는 signals(JSON, ParsedStoreSignal 직렬화).
 *
 * 근거 시각 (v3 P2-2): {@code signalObservedAt} 은 이 신호 값을 실제로 본 관측의 시각이고,
 * {@code StoreSyncState.lastSuccessAt}(수집에 성공한 시각)과 다르다. 내용 해시가 같아 스냅샷이 저장되지 않은
 * 방문도 리뷰·신호는 바뀔 수 있으므로, 그런 관측으로도 이 행을 갱신한다.
 * 그때는 원본이 place_snapshot 에 없으므로 {@code payloadRetained=false} 로 남긴다 — 파서가 바뀌면
 * 재파싱이 아니라 재수집 대상이고, 낡은 스냅샷으로 되돌려서는 안 된다.
 */
@Entity
@Table(name = "store_signal")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreSignal {

	@Id
	@Column(name = "store_id")
	private Long storeId;

	@Column(name = "snapshot_id", nullable = false)
	private Long snapshotId;

	@Column(name = "fetched_at", nullable = false)
	private LocalDateTime fetchedAt;

	@Column(length = 100)
	private String category;

	@Column(name = "visitor_reviews_score")
	private Double visitorReviewsScore;

	@Column(name = "visitor_reviews_total", nullable = false)
	private int visitorReviewsTotal;

	@Column(name = "recommend_menu_count", nullable = false)
	private int recommendMenuCount;

	@Column(name = "new_opening", nullable = false)
	private boolean newOpening;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String signals;

	@Column(name = "parser_version", nullable = false)
	private int parserVersion;

	/** 파서 결과(ParsedStoreSignal) 기준 해시 — 원본의 순서·표현 잡음이 아니라 의미 변경만 잡는다 */
	@Column(name = "signal_hash", length = 64)
	private String signalHash;

	/** 이 신호 값을 본 관측의 시각 — last_success_at(수집 성공 시각)과 분리한다 */
	@Column(name = "signal_observed_at")
	private LocalDateTime signalObservedAt;

	@Column(name = "observation_id", length = 64)
	private String observationId;

	/** 이 신호를 만든 원본이 place_snapshot 에 남아 있는지 — false 면 재파싱 불가(재수집 대상) */
	@Column(name = "payload_retained", nullable = false)
	private boolean payloadRetained = true;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	public StoreSignal(Long storeId, Long snapshotId, LocalDateTime fetchedAt, String category,
			Double visitorReviewsScore, int visitorReviewsTotal, int recommendMenuCount, boolean newOpening,
			String signals, int parserVersion, String signalHash, LocalDateTime signalObservedAt,
			String observationId, boolean payloadRetained) {
		this.storeId = storeId;
		this.createdAt = LocalDateTime.now();
		refresh(snapshotId, fetchedAt, category, visitorReviewsScore, visitorReviewsTotal, recommendMenuCount,
			newOpening, signals, parserVersion);
		this.signalHash = signalHash;
		this.signalObservedAt = signalObservedAt;
		this.observationId = observationId;
		this.payloadRetained = payloadRetained;
	}

	/** 갱신 — 내용 필드를 전부 덮어쓴다 */
	public void refresh(Long snapshotId, LocalDateTime fetchedAt, String category, Double visitorReviewsScore,
			int visitorReviewsTotal, int recommendMenuCount, boolean newOpening, String signals,
			int parserVersion) {
		this.snapshotId = snapshotId;
		this.fetchedAt = fetchedAt;
		this.category = category;
		this.visitorReviewsScore = visitorReviewsScore;
		this.visitorReviewsTotal = visitorReviewsTotal;
		this.recommendMenuCount = recommendMenuCount;
		this.newOpening = newOpening;
		this.signals = signals;
		this.parserVersion = parserVersion;
		this.updatedAt = LocalDateTime.now();
	}

	/**
	 * 관측 근거 기록 — 신호 값이 어느 관측에서 왔는지 남긴다.
	 * 관측 시각은 뒤로 가지 않는다: 늦게 도착한 과거 관측이 최신 근거 시각을 되돌리면 안 된다.
	 */
	public void recordObservation(String signalHash, LocalDateTime observedAt, String observationId,
			boolean payloadRetained) {
		this.signalHash = signalHash;
		if (observedAt != null && (this.signalObservedAt == null || observedAt.isAfter(this.signalObservedAt))) {
			this.signalObservedAt = observedAt;
		}
		this.observationId = observationId;
		this.payloadRetained = payloadRetained;
	}
}
