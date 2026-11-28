package sweetgirl.dataops.proposal.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.enums.ProposalTargetFamily;

public interface ChangeProposalRepository extends JpaRepository<ChangeProposal, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM ChangeProposal p WHERE p.id=:id")
	Optional<ChangeProposal> findForApplyUpdate(@Param("id") Long id);

	/** Replay-created proposals always require the second approval, even outside the agent runner. */
	@Query(value = "SELECT EXISTS (SELECT 1 FROM response_replay_proposal WHERE proposal_id=:id AND relation='CREATED')",
		nativeQuery = true)
	boolean requiresAgentManifest(@Param("id") Long id);

	@Query(value = "SELECT EXISTS (SELECT 1 FROM change_proposal candidate JOIN change_proposal blocker "
		+ "ON candidate.store_id=blocker.store_id AND candidate.target=blocker.target "
		+ "WHERE candidate.id=:id AND blocker.id<>candidate.id AND blocker.status IN ('APPLYING','UNKNOWN'))",nativeQuery=true)
	boolean hasUnresolvedTarget(@Param("id") Long id);

	Optional<ChangeProposal> findByStoreIdAndTargetAndStatus(Long storeId, String target, ProposalStatus status);

	/** 같은 대상의 진행 중(PENDING·APPLYING·UNKNOWN) 제안 — 부분 유니크 uq_proposal_in_flight 와 같은 범위 */
	Optional<ChangeProposal> findFirstByStoreIdAndTargetAndStatusIn(Long storeId, String target,
			Collection<ProposalStatus> statuses);

	boolean existsByStoreIdAndTargetAndNewValueAndStatus(Long storeId, String target, String newValue,
			ProposalStatus status);

	Page<ChangeProposal> findByStatusOrderByCreatedAtDesc(ProposalStatus status, Pageable pageable);

	Page<ChangeProposal> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

	long countByStatus(ProposalStatus status);

	/**
	 * 반영 점유 (compare-and-set) — PENDING 일 때만 APPLYING 으로 넘긴다. 반환 0 이면 이미 다른 요청이 점유했거나
	 * 처리가 끝난 제안이다. 벌크 UPDATE 라 영속성 컨텍스트를 거치지 않는다 — 호출자는 점유 후 다시 읽는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE ChangeProposal p SET p.status = sweetgirl.dataops.proposal.enums.ProposalStatus.APPLYING, "
		+ "p.operationId = :operationId, p.applyStartedAt = :startedAt, p.updatedAt = :startedAt "
		+ "WHERE p.id = :id AND p.status = sweetgirl.dataops.proposal.enums.ProposalStatus.PENDING")
	int claimForApply(@Param("id") Long id, @Param("operationId") UUID operationId,
			@Param("startedAt") LocalDateTime startedAt);

	/** 메모만 갱신한다. 먼저 기록된 충돌은 우선 보존하며, DB의 현재 값으로 확인한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = false)
	@Query("UPDATE ChangeProposal p SET p.reviewNote = :note, p.updatedAt = CURRENT_TIMESTAMP "
		+ "WHERE p.id = :id AND p.source = :source "
		+ "AND p.status = sweetgirl.dataops.proposal.enums.ProposalStatus.PENDING "
		+ "AND (p.reviewNote IS NULL OR (LOCATE('SOURCE_CONFLICT', p.reviewNote) = 0 "
		+ "AND LOCATE('PRODUCER_CONFLICT', p.reviewNote) = 0 AND LOCATE('다른 값을 제안', p.reviewNote) = 0))")
	int updatePendingReviewNote(@Param("id") Long id,
		@Param("source") sweetgirl.dataops.reconcile.enums.ProvenanceSource source, @Param("note") String note);

	/**
	 * 범위 정리 (v3 P1-2) — 한 가게에서 이 producer 가 이 묶음들을 다시 판정한 뒤, 이번 판정에서 나오지
	 * 않은 자기 PENDING 제안만 SUPERSEDED 로 넘긴다. 삭제가 아니라 세대 교체다.
	 *
	 * 범위 밖은 건드리지 않는다: 다른 가게, 다른 producer(LEGACY_UNKNOWN 포함), 이 판정이 보지 않은 묶음,
	 * 그리고 PENDING 이 아닌 것(APPLYING·APPROVED·REJECTED). 판정이 중간에 실패하면 호출자가 이 정리를
	 * 아예 실행하지 않으므로 그 가게의 이전 제안이 그대로 남는다.
	 *
	 * 영속성 컨텍스트를 비우지 않는다(clearAutomatically=false) — 이 쿼리는 재판정 트랜잭션 <b>안에서</b>,
	 * 스냅샷 제출이 sync_state·신호를 고친 뒤에 불린다. 비우면 flush 전인 그 변경이 버려진다
	 * (2026-09-26 표본 100곳 중 18곳: 스냅샷은 저장됐는데 방문·해시·다음 예약이 그대로였다).
	 * 대신 실행 전에 flush 해서 대기 중인 변경을 먼저 내보낸다. 이 호출 뒤에 같은 트랜잭션에서
	 * ChangeProposal 을 다시 읽어야 한다면 호출자가 명시적으로 새로 읽는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = false)
	@Query("UPDATE ChangeProposal p "
		+ "SET p.status = sweetgirl.dataops.proposal.enums.ProposalStatus.SUPERSEDED, "
		+ "p.reviewNote = :note, p.updatedAt = CURRENT_TIMESTAMP "
		+ "WHERE p.storeId = :storeId AND p.producer = :producer AND p.targetFamily IN :families "
		+ "AND p.status = sweetgirl.dataops.proposal.enums.ProposalStatus.PENDING "
		+ "AND (:survivorCount = 0 OR p.id NOT IN :survivors)")
	int expireOutOfGeneration(@Param("storeId") Long storeId, @Param("producer") ProposalProducer producer,
			@Param("families") Collection<ProposalTargetFamily> families,
			@Param("survivors") Collection<Long> survivors, @Param("survivorCount") int survivorCount,
			@Param("note") String note);

	/** Never expire another source's proposals or unseen delivery-menu targets. */
	@Modifying
	@Query("UPDATE ChangeProposal p SET p.status = sweetgirl.dataops.proposal.enums.ProposalStatus.SUPERSEDED, "
		+ "p.reviewNote = :note, p.updatedAt = CURRENT_TIMESTAMP "
		+ "WHERE p.storeId = :storeId AND p.producer = :producer AND p.source = :source AND p.targetFamily IN :families "
		+ "AND p.status = sweetgirl.dataops.proposal.enums.ProposalStatus.PENDING "
		+ "AND (:observedOnly = false OR p.target IN :targets) "
		+ "AND (:survivorCount = 0 OR p.id NOT IN :survivors)")
	int expireMenuSourceOutOfGeneration(@Param("storeId") Long storeId, @Param("producer") ProposalProducer producer,
		@Param("source") sweetgirl.dataops.reconcile.enums.ProvenanceSource source,
		@Param("families") Collection<ProposalTargetFamily> families, @Param("observedOnly") boolean observedOnly,
		@Param("targets") Collection<String> targets, @Param("survivors") Collection<Long> survivors,
		@Param("survivorCount") int survivorCount, @Param("note") String note);

	/** 같은 대상에 다른 producer 의 진행 중 제안이 있는지 — 지우지 않고 보존하기 위해 본다 */
	List<ChangeProposal> findByStoreIdAndTargetAndStatusInOrderByIdAsc(Long storeId, String target,
			Collection<ProposalStatus> statuses);
}
