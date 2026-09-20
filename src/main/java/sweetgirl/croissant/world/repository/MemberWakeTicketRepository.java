package sweetgirl.croissant.world.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.world.entity.MemberWakeTicket;

public interface MemberWakeTicketRepository extends JpaRepository<MemberWakeTicket, Long> {

	int countByMemberIdAndUsedAtIsNull(Long memberId);
}
