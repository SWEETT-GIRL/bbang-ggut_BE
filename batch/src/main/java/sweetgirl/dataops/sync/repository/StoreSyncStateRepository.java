package sweetgirl.dataops.sync.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import jakarta.persistence.LockModeType;

public interface StoreSyncStateRepository extends JpaRepository<StoreSyncState, Long> {

	/**
	 * 원자적 due 클레임 (v3 P2-1) — 한 문장으로 고르고 스탬프한다. {@code FOR UPDATE SKIP LOCKED} 로
	 * 다른 배부가 이미 잡은 행을 건너뛰므로 워커 둘이 같은 가게를 받지 않는다.
	 * 배부마다 attempt_seq 를 올리고 claim_token 을 새로 발급한다 — 늦게 도착한 이전 시도를 가려내는 근거다.
	 *
	 * 네이티브 쿼리인 이유: JPQL 은 SKIP LOCKED 를 표현할 수 없고, 조회와 스탬프를 두 문장으로 나누면
	 * 그 사이에 다른 배부가 끼어들 수 있다(지금의 결함).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = "UPDATE store_sync_state target "
		+ "SET last_attempt_at = :now, claimed_at = :now, claim_token = :token, attempt_seq = target.attempt_seq + 1 "
		+ "FROM (SELECT id FROM store_sync_state "
		+ "      WHERE state = :#{#state.name()} AND next_due_at <= :now "
		+ "        AND (last_attempt_at IS NULL OR last_attempt_at < :leaseExpiry) "
		+ "      ORDER BY next_due_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED) picked "
		+ "WHERE target.id = picked.id", nativeQuery = true)
	int claimDue(@Param("state") SyncStateStatus state, @Param("now") LocalDateTime now,
			@Param("leaseExpiry") LocalDateTime leaseExpiry, @Param("limit") int limit,
			@Param("token") java.util.UUID token);

	/** 방금 클레임한 항목 — claimDue 직후 같은 토큰으로 읽는다 */
	List<StoreSyncState> findByClaimTokenOrderByNextDueAtAsc(java.util.UUID claimToken);

	/** due 목록 — 소프트 리스: 최근 배부분(lastAttemptAt >= leaseExpiry)은 제외. 부분 인덱스 idx_sync_state_due 활용 */
	@Query("SELECT s FROM StoreSyncState s "
		+ "WHERE s.state = :state AND s.nextDueAt <= :now "
		+ "AND (s.lastAttemptAt IS NULL OR s.lastAttemptAt < :leaseExpiry) "
		+ "ORDER BY s.nextDueAt ASC")
	List<StoreSyncState> findDue(
			@Param("state") SyncStateStatus state,
			@Param("now") LocalDateTime now,
			@Param("leaseExpiry") LocalDateTime leaseExpiry,
			Pageable pageable);

	@Query("SELECT s.storeId FROM StoreSyncState s WHERE s.source = :source")
	List<Long> findStoreIdsBySource(@Param("source") SyncSource source);

	Optional<StoreSyncState> findByStoreIdAndSource(Long storeId, SyncSource source);

	/** Snapshot writes are serialized after the independently committed observation. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM StoreSyncState s WHERE s.storeId = :storeId AND s.source = :source")
	Optional<StoreSyncState> findForSnapshotUpdate(@Param("storeId") Long storeId, @Param("source") SyncSource source);

	@Query("SELECT s FROM StoreSyncState s "
		+ "WHERE (:tier IS NULL OR s.tier = :tier) AND (:state IS NULL OR s.state = :state)")
	Page<StoreSyncState> findByFilters(
			@Param("tier") SyncTier tier,
			@Param("state") SyncStateStatus state,
			Pageable pageable);

	/** 전체 스케줄 보류 — next_due_at NULL 은 due 쿼리에서 영구 제외 (PARKED 와 달리 state 는 ACTIVE 유지) */
	@Modifying
	@Query("UPDATE StoreSyncState s SET s.nextDueAt = NULL WHERE s.source = :source AND s.state = :state")
	int clearSchedule(@Param("source") SyncSource source, @Param("state") SyncStateStatus state);

	@Modifying
	@Query("UPDATE StoreSyncState s SET s.nextDueAt = :dueAt "
		+ "WHERE s.source = :source AND s.state = :state AND s.storeId IN :storeIds")
	int scheduleStores(
			@Param("source") SyncSource source,
			@Param("state") SyncStateStatus state,
			@Param("storeIds") List<Long> storeIds,
			@Param("dueAt") LocalDateTime dueAt);
}
