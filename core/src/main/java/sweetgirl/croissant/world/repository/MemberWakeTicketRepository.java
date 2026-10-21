package sweetgirl.croissant.world.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.MemberWakeTicket;

public interface MemberWakeTicketRepository extends JpaRepository<MemberWakeTicket, Long> {

	int countByMemberIdAndUsedAtIsNull(Long memberId);

	/**
	 * 티켓은 소유자만 바꾸면 된다. 추천 티켓의 유니크 인덱스가 주인이 아니라 추천받은 사람
	 * (ref_member) 하나에만 걸려 있어, 옮겨도 충돌할 짝이 애초에 없다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_wake_tickets SET member_id = :targetId WHERE member_id = :guestId
			""", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	/** 가장 먼저 받은 티켓부터 쓴다. */
	Optional<MemberWakeTicket> findFirstByMemberIdAndUsedAtIsNullOrderByIdAsc(Long memberId);

	/**
	 * 티켓을 쓴 것으로 표시한다. 두 요청이 같은 티켓을 집어도 한쪽만 1 을 받는다 —
	 * 티켓 한 장으로 둘을 깨우는 일이 없다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_wake_tickets
			SET used_at = CURRENT_TIMESTAMP
			WHERE id = :ticketId AND used_at IS NULL
			""", nativeQuery = true)
	int useTicket(@Param("ticketId") Long ticketId);
}
