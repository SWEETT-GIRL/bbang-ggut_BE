package sweetgirl.dataops.sync.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.exception.SyncStateException;

class StoreSyncStateTest {

	private StoreSyncState createState(SyncTier tier) {
		return StoreSyncState.builder()
			.storeId(1L)
			.source(SyncSource.NAVER)
			.externalId("naver-123")
			.tier(tier)
			.build();
	}

	@Test
	void 생성_시_ACTIVE_상태로_즉시_due_다() {
		StoreSyncState syncState = createState(SyncTier.T1);

		assertThat(syncState.getState()).isEqualTo(SyncStateStatus.ACTIVE);
		assertThat(syncState.getNextDueAt()).isBeforeOrEqualTo(LocalDateTime.now());
		assertThat(syncState.getVisitCount()).isZero();
	}

	@Test
	void recordSuccess_변경_방문은_두_카운터를_올리고_주기와_지터로_재스케줄한다() {
		StoreSyncState syncState = createState(SyncTier.T1);

		syncState.recordSuccess(true, "hash-1");

		assertThat(syncState.getVisitCount()).isEqualTo(1);
		assertThat(syncState.getChangeCount()).isEqualTo(1);
		assertThat(syncState.getContentHash()).isEqualTo("hash-1");
		assertThat(syncState.getConsecutiveFailures()).isZero();
		// T1 주기 7일 ± 지터 10%
		LocalDateTime lowerBound = syncState.getLastSuccessAt().plusHours((long)(7 * 24 * 0.9));
		LocalDateTime upperBound = syncState.getLastSuccessAt().plusHours((long)(7 * 24 * 1.1) + 1);
		assertThat(syncState.getNextDueAt()).isBetween(lowerBound, upperBound);
	}

	@Test
	void recordSuccess_무변경_방문은_visit만_올린다() {
		StoreSyncState syncState = createState(SyncTier.T0);

		syncState.recordSuccess(false, "hash-1");

		assertThat(syncState.getVisitCount()).isEqualTo(1);
		assertThat(syncState.getChangeCount()).isZero();
	}

	@Test
	void recordFailure_지수_백오프로_재스케줄한다() {
		StoreSyncState syncState = createState(SyncTier.T1);

		syncState.recordFailure();
		assertThat(syncState.getConsecutiveFailures()).isEqualTo(1);
		assertThat(syncState.getNextDueAt())
			.isAfter(LocalDateTime.now().plusHours(23))
			.isBefore(LocalDateTime.now().plusHours(25));

		syncState.recordFailure();
		assertThat(syncState.getNextDueAt())
			.isAfter(LocalDateTime.now().plusHours(47))
			.isBefore(LocalDateTime.now().plusHours(49));
	}

	@Test
	void recordFailure_연속_10회면_UNLINKED_전환한다() {
		StoreSyncState syncState = createState(SyncTier.T1);

		for (int i = 0; i < 10; i++) {
			syncState.recordFailure();
		}

		assertThat(syncState.getState()).isEqualTo(SyncStateStatus.UNLINKED);
		assertThat(syncState.getNextDueAt()).isNull();
	}

	@Test
	void recordSuccess_성공하면_연속_실패가_리셋된다() {
		StoreSyncState syncState = createState(SyncTier.T1);
		syncState.recordFailure();

		syncState.recordSuccess(false, "hash-1");

		assertThat(syncState.getConsecutiveFailures()).isZero();
	}

	@Test
	void park_는_스케줄에서_제외한다() {
		StoreSyncState syncState = createState(SyncTier.T1);

		syncState.park();

		assertThat(syncState.getState()).isEqualTo(SyncStateStatus.PARKED);
		assertThat(syncState.getNextDueAt()).isNull();
	}

	@Test
	void park_는_ACTIVE_에서만_허용된다() {
		StoreSyncState syncState = createState(SyncTier.T1);
		syncState.park();

		assertThatThrownBy(syncState::park).isInstanceOf(SyncStateException.class);
	}

	@Test
	void unpark_는_즉시_due_로_되돌린다() {
		StoreSyncState syncState = createState(SyncTier.T1);
		syncState.park();

		syncState.unpark();

		assertThat(syncState.getState()).isEqualTo(SyncStateStatus.ACTIVE);
		assertThat(syncState.getNextDueAt()).isBeforeOrEqualTo(LocalDateTime.now());
	}

	@Test
	void unpark_는_PARKED_에서만_허용된다() {
		StoreSyncState syncState = createState(SyncTier.T1);

		assertThatThrownBy(syncState::unpark).isInstanceOf(SyncStateException.class);
	}

	@Test
	void markAttempt_는_배부_스탬프만_남긴다() {
		StoreSyncState syncState = createState(SyncTier.T1);
		LocalDateTime attemptAt = LocalDateTime.now();

		syncState.markAttempt(attemptAt);

		assertThat(syncState.getLastAttemptAt()).isEqualTo(attemptAt);
		assertThat(syncState.getVisitCount()).isZero();
	}
}
