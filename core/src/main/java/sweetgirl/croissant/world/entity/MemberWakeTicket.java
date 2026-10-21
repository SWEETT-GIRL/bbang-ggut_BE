package sweetgirl.croissant.world.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.world.enums.WakeTicketSource;

/**
 * 광고를 건너뛰고 친구 하나를 바로 깨우는 티켓.
 *
 * <p>{@code refMember} 는 나를 통해 가입한 사람이다. 같은 사람으로 두 장이 나오지 않도록
 * REFERRAL 에는 부분 유니크 인덱스가 걸려 있다.
 */
@Entity
@Table(name = "member_wake_tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberWakeTicket {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private WakeTicketSource source;

	@Column(name = "ref_member")
	private Long refMember;

	private LocalDateTime usedAt;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	public static MemberWakeTicket issue(Long memberId, WakeTicketSource source, Long refMember) {
		MemberWakeTicket ticket = new MemberWakeTicket();
		ticket.memberId = memberId;
		ticket.source = source;
		ticket.refMember = refMember;
		ticket.createdAt = LocalDateTime.now();
		return ticket;
	}

	public boolean isUsed() {
		return usedAt != null;
	}
}
