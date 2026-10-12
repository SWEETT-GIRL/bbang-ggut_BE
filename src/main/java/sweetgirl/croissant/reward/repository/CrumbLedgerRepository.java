package sweetgirl.croissant.reward.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.CrumbLedgerEntry;
import sweetgirl.croissant.reward.enums.CrumbReason;

public interface CrumbLedgerRepository extends JpaRepository<CrumbLedgerEntry, Long> {

	/**
	 * 원장에 한 줄 넣는다. 넣었으면 1, 같은 키로 이미 있으면 0 이다.
	 *
	 * <p>먼저 읽어 보고 없으면 넣는 식이면 재시도가 나란히 들어왔을 때 둘 다 "없다"를 보고 둘 다 준다.
	 * 유니크 충돌을 DB 가 판단하게 두고 그 결과만 받는다.
	 * 충돌 대상을 적지 않은 이유: 테스트 DB(H2)가 {@code ON CONFLICT (컬럼)} 을 읽지 못한다.
	 * 이 표에서 넣다가 부딪힐 수 있는 유니크는 {@code (member_id, idempotency_key)} 하나뿐이다(id 는 DB 가 매긴다).
	 */
	@Modifying
	@Query(value = """
			INSERT INTO crumb_ledger
				(member_id, amount, reason, idempotency_key, balance_after, earned_on, ref_type, ref_id, memo,
				created_at)
			VALUES (:memberId, :amount, :reason, :idempotencyKey, :balanceAfter, :earnedOn, :refType, :refId, :memo,
				:now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("memberId") Long memberId,
			@Param("amount") int amount,
			@Param("reason") String reason,
			@Param("idempotencyKey") String idempotencyKey,
			@Param("balanceAfter") int balanceAfter,
			@Param("earnedOn") LocalDate earnedOn,
			@Param("refType") String refType,
			@Param("refId") Long refId,
			@Param("memo") String memo,
			@Param("now") LocalDateTime now);

	/** 그날 이 이유로 받은 횟수 — 하루 상한을 센다. 지갑을 잠근 뒤에 불러야 뜻이 있다. */
	long countByMemberIdAndReasonAndEarnedOn(Long memberId, CrumbReason reason, LocalDate earnedOn);

	/** 그날 받은 양(쓴 것은 빼지 않는다). */
	@Query("""
			SELECT COALESCE(SUM(entry.amount), 0) FROM CrumbLedgerEntry entry
			WHERE entry.memberId = :memberId AND entry.earnedOn = :earnedOn AND entry.amount > 0
			""")
	long sumGainedOn(@Param("memberId") Long memberId, @Param("earnedOn") LocalDate earnedOn);

	/** 받은 내역 첫 장 — 최신순. */
	List<CrumbLedgerEntry> findByMemberIdOrderByIdDesc(Long memberId, Pageable pageable);

	/** 받은 내역 다음 장 — 앞 장의 마지막 id 보다 오래된 것. */
	List<CrumbLedgerEntry> findByMemberIdAndIdLessThanOrderByIdDesc(Long memberId, Long id, Pageable pageable);
}
