package sweetgirl.dataops.sync.entity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.dataops.common.entity.BaseTimeEntity;
import sweetgirl.dataops.sync.code.SyncStateErrorCode;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.exception.SyncStateException;

/**
 * 가게×소스 동기화 상태 — next_due_at 이 곧 워크리스트다.
 * 카운터·백오프·다음 주기 계산을 전부 이 엔티티에 캡슐화한다 (설계서 7.1).
 */
@Entity
@Table(name = "store_sync_state", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "source"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreSyncState extends BaseTimeEntity {

	private static final double JITTER_RATIO = 0.1;
	private static final int UNLINK_FAILURE_THRESHOLD = 10;
	private static final int BACKOFF_EXPONENT_CAP = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SyncSource source;

	@Column(name = "external_id", length = 80)
	private String externalId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private SyncTier tier;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SyncStateStatus state;

	@Column(name = "next_due_at")
	private LocalDateTime nextDueAt;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;

	/** 이 항목을 내보낸 배부의 토큰 — 같은 토큰의 제출만 그 배부의 결과로 인정한다 (v3 P2-1) */
	@Column(name = "claim_token")
	private java.util.UUID claimToken;

	/** attempt 세대 — 배부마다 1씩 오른다. 제출의 세대가 낮으면 늦게 도착한 이전 시도다 */
	@Column(name = "attempt_seq", nullable = false)
	private long attemptSeq;

	@Column(name = "claimed_at")
	private LocalDateTime claimedAt;

	@Column(name = "last_success_at")
	private LocalDateTime lastSuccessAt;

	@Column(name = "content_hash", length = 64)
	private String contentHash;

	/** 저장된 content_hash 를 계산한 세대 — 비교 시 이 세대로 계산해야 세대 전환이 가짜 변경이 되지 않는다 */
	@Column(name = "content_hash_version", nullable = false)
	private short contentHashVersion = 1;

	@Column(name = "non_menu_hash", length = 64)
	private String nonMenuHash;
	@Column(name = "menu_hash", length = 64)
	private String menuHash;
	@Column(name = "scope_hash_version", nullable = false)
	private short scopeHashVersion;
	@Column(name = "store_fields_observed_at")
	private LocalDateTime storeFieldsObservedAt;
	@Column(name = "menu_observed_at")
	private LocalDateTime menuObservedAt;

	/**
	 * 정기 영업시간 기억 (v3 P2) — 요일별로 마지막에 본 날짜 없는 정기 값. 영업시간 변경은 해시가 아니라 이것과
	 * 공통 요일끼리 비교해 판정한다(RegularHoursTracker). NULL 이면 아직 추적 전이다.
	 */
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
	@Column(name = "hours_known")
	private String hoursKnown;

	@Column(name = "visit_count", nullable = false)
	private int visitCount;

	@Column(name = "change_count", nullable = false)
	private int changeCount;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures;

	@Builder
	public StoreSyncState(Long storeId, SyncSource source, String externalId, SyncTier tier) {
		this.storeId = storeId;
		this.source = source;
		this.externalId = externalId;
		this.tier = tier != null ? tier : SyncTier.T1;
		this.state = SyncStateStatus.ACTIVE;
		this.nextDueAt = LocalDateTime.now();
	}

	/**
	 * 등록 후속 보완 (v3 P1-3) — 이미 있는 행에 빠진 연결을 채운다. 반환: 무언가 바뀌었는지.
	 *
	 * 외부 ID 가 비어 있으면 채우고, 아직 한 번도 수집되지 않았으면 즉시 due 로 되돌려 초기 수집을 예약한다
	 * (sync_state 의 due 가 그 예약이다 — 사람의 추가 호출에 의존하지 않는다).
	 * PARKED·UNLINKED 는 사람이 정한 상태이므로 되살리지 않는다.
	 */
	public boolean ensureLinked(String externalId) {
		boolean changed = false;
		if (this.externalId == null && externalId != null) {
			this.externalId = externalId;
			changed = true;
		}
		if (this.state == SyncStateStatus.ACTIVE && this.lastSuccessAt == null
			&& (this.nextDueAt == null || this.nextDueAt.isAfter(LocalDateTime.now()))) {
			this.nextDueAt = LocalDateTime.now();
			changed = true;
		}
		return changed;
	}

	/** due 배부 시 스탬프 — 소프트 리스 (최근 배부분은 due 쿼리에서 제외된다) */
	public void markAttempt(LocalDateTime attemptAt) {
		this.lastAttemptAt = attemptAt;
	}

	/** 스냅샷 수집 성공 — 무변경 방문도 visit 은 센다 (스냅샷 insert 생략 시 n 복원 불가하므로 여기가 유일 기록) */
	public void recordSuccess(boolean changed, String newContentHash) {
		recordSuccess(changed, newContentHash, this.contentHashVersion);
	}

	/** @param hashVersion 저장하는 해시의 세대 — 비교 기준이 어느 세대였는지 남긴다 */
	public void recordSuccess(boolean changed, String newContentHash, short hashVersion) {
		this.contentHashVersion = hashVersion;
		this.visitCount++;
		if (changed) {
			this.changeCount++;
		}
		this.contentHash = newContentHash;
		this.lastSuccessAt = LocalDateTime.now();
		this.consecutiveFailures = 0;
		this.nextDueAt = this.lastSuccessAt.plusSeconds(jitteredSeconds(tier.getPeriod()));
	}

	/** A partial success advances only the accepted store/hours scope. Menu freshness stays unchanged. */
	public void recordScopedSuccess(boolean changed, String fullHash, short fullVersion,
			String nonMenuHash, String menuHash, short scopeVersion, LocalDateTime observedAt) {
		recordSuccess(changed, fullHash == null ? this.contentHash : fullHash,
			fullHash == null ? this.contentHashVersion : fullVersion);
		this.nonMenuHash = nonMenuHash;
		this.scopeHashVersion = scopeVersion;
		this.storeFieldsObservedAt = observedAt;
		if (menuHash != null) {
			this.menuHash = menuHash;
			this.menuObservedAt = observedAt;
		}
	}

	/** fetch 실패 — 지수 백오프(1일→2→4…), 연속 10회면 place_id 무효로 판단해 UNLINKED */
	public void recordFailure() {
		this.consecutiveFailures++;
		if (this.consecutiveFailures >= UNLINK_FAILURE_THRESHOLD) {
			unlink();
			return;
		}
		int exponent = Math.min(this.consecutiveFailures - 1, BACKOFF_EXPONENT_CAP);
		this.nextDueAt = LocalDateTime.now().plusDays(1L << exponent);
	}

	public void park() {
		if (this.state != SyncStateStatus.ACTIVE) {
			throw new SyncStateException(
				SyncStateErrorCode.SYNC_STATE_TRANSITION_NOT_ALLOWED,
				String.format("%s 상태에서는 park 할 수 없습니다.", this.state));
		}
		this.state = SyncStateStatus.PARKED;
		this.nextDueAt = null;
	}

	/** 해제 즉시 due — 수동 즉시 재크롤 용도를 겸한다 */
	public void unpark() {
		if (this.state != SyncStateStatus.PARKED) {
			throw new SyncStateException(
				SyncStateErrorCode.SYNC_STATE_TRANSITION_NOT_ALLOWED,
				String.format("%s 상태에서는 unpark 할 수 없습니다.", this.state));
		}
		this.state = SyncStateStatus.ACTIVE;
		this.nextDueAt = LocalDateTime.now();
	}

	public void unlink() {
		this.state = SyncStateStatus.UNLINKED;
		this.nextDueAt = null;
	}

	public void changeTier(SyncTier newTier) {
		this.tier = newTier;
	}

	/** 해시 정책 변경 시 재베이스라인 — 카운터·스케줄은 건드리지 않는다 */
	public void rebaselineHash(String newContentHash) {
		this.contentHash = newContentHash;
	}

	/**
	 * 해시 세대 재기준 (v3 P2) — 최신 스냅샷으로 계산한 해시·세대·정기 영업시간 기억을 한 번에 세운다.
	 * 카운터·스케줄은 건드리지 않는다. 이것 없이 세대를 올리면 첫 방문이 옛 세대 비교(영업시간 포함)로 한 번 더 흔들린다.
	 */
	public void rebaseline(String newContentHash, short version, String hoursKnown) {
		this.contentHash = newContentHash;
		this.contentHashVersion = version;
		this.hoursKnown = hoursKnown;
		this.nonMenuHash = null;
		this.menuHash = null;
		this.scopeHashVersion = 0;
	}

	/** 관측마다 정기 영업시간 기억을 갱신한다 — 변경이든 무변경이든 받아들인 관측이면 */
	public void rememberHours(String hoursKnown) {
		this.hoursKnown = hoursKnown;
	}

	/**
	 * 해시 세대 전환 (v3 P2-1) — 같은 내용인데 세대만 다른 경우다. 카운터·스케줄을 건드리지 않는다:
	 * 세대 전환 자체는 가게가 바뀐 것이 아니므로 change_count 에 합산하면 변경률이 오염된다.
	 */
	public void upgradeContentHashVersion(String newContentHash, short version) {
		this.contentHash = newContentHash;
		this.contentHashVersion = version;
	}

	/** 저장 전용 소스(INSTAGRAM 등) — 크롤 스케줄에서 제외 (state 는 ACTIVE 유지, PARKED 와 구분) */
	public void holdSchedule() {
		this.nextDueAt = null;
	}

	public void updateExternalId(String newExternalId) {
		this.externalId = newExternalId;
	}

	private long jitteredSeconds(Duration period) {
		double factor = 1 - JITTER_RATIO + ThreadLocalRandom.current().nextDouble() * JITTER_RATIO * 2;
		return (long)(period.getSeconds() * factor);
	}
}
