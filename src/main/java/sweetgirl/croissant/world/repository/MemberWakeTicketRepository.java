package sweetgirl.croissant.world.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.MemberWakeTicket;

public interface MemberWakeTicketRepository extends JpaRepository<MemberWakeTicket, Long> {

	int countByMemberIdAndUsedAtIsNull(Long memberId);

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
