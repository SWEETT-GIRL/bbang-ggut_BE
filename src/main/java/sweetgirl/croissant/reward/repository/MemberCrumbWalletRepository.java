package sweetgirl.croissant.reward.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberCrumbWallet;

public interface MemberCrumbWalletRepository extends JpaRepository<MemberCrumbWallet, Long> {

	/**
	 * 지갑이 없으면 0 으로 만든다. 새로 만들었으면 1, 이미 있으면 0 이다.
	 *
	 * <p>같은 회원의 첫 요청이 동시에 둘 들어와도 뒤에 온 쪽이 PK 로 깨지지 않게 넣기를 건너뛴다.
	 * 충돌 대상을 적지 않은 이유: 테스트 DB(H2)가 {@code ON CONFLICT (컬럼)} 을 읽지 못한다. 이 표의 유니크는 PK 하나뿐이다.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO member_crumb_wallet (member_id, balance, created_at)
			VALUES (:memberId, 0, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int createIfAbsent(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

	/**
	 * 지갑 행을 잠그고 잔액을 읽는다. 트랜잭션이 끝날 때까지 이 회원의 다른 적립·차감은 여기서 기다린다.
	 *
	 * <p>상한을 세는 일과 지급 사이에 다른 요청이 끼어들지 못하게 하는 유일한 줄이다.
	 * 회원 행이 아니라 지갑 행을 잠그는 이유: 어차피 고칠 행이고, 닉네임 뽑기처럼 회원 행을 잠그는
	 * 다른 기능과 서로 기다리지 않는다.
	 */
	@Query(value = "SELECT balance FROM member_crumb_wallet WHERE member_id = :memberId FOR UPDATE", nativeQuery = true)
	Optional<Integer> lockBalance(@Param("memberId") Long memberId);

	@Query(value = "SELECT balance FROM member_crumb_wallet WHERE member_id = :memberId", nativeQuery = true)
	Optional<Integer> findBalance(@Param("memberId") Long memberId);

	/** 이관 선물을 준 때. 지갑이 없거나 아직 주지 않았으면 비어 있다. */
	@Query("SELECT wallet.carriedOverAt FROM MemberCrumbWallet wallet WHERE wallet.memberId = :memberId")
	Optional<LocalDateTime> findCarriedOverAt(@Param("memberId") Long memberId);

	/** 잔액을 움직인다(차감은 음수). 반드시 {@link #lockBalance} 로 잠근 뒤에 부른다. */
	@Modifying
	@Query(value = """
			UPDATE member_crumb_wallet
			SET balance = balance + :amount, updated_at = :now
			WHERE member_id = :memberId
			""", nativeQuery = true)
	int addBalance(@Param("memberId") Long memberId, @Param("amount") int amount, @Param("now") LocalDateTime now);

	/** 이관 선물을 준 때를 남긴다. 이미 남겼으면 0 이다. */
	@Modifying
	@Query(value = """
			UPDATE member_crumb_wallet
			SET carried_over_at = :now, updated_at = :now
			WHERE member_id = :memberId AND carried_over_at IS NULL
			""", nativeQuery = true)
	int markCarriedOver(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);
}
