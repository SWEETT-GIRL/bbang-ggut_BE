package sweetgirl.croissant.reward.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 초대 한 건 — 초대받은 사람이 초대한 사람의 코드를 넣었다는 사실.
 * 읽기 전용이다. 행은 {@code MemberInviteRepository.insertIfAbsent} 로만 생긴다.
 */
@Entity
@Table(name = "member_invites", uniqueConstraints = @UniqueConstraint(columnNames = "invitee_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberInvite {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "inviter_id", nullable = false)
	private Long inviterId;

	@Column(name = "invitee_id", nullable = false)
	private Long inviteeId;

	/** 코드를 넣은 때. 여기서 30 일이 성사 기한이다. */
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 초대받은 사람이 첫 빵을 기록해 초대가 성사된 때. */
	private LocalDateTime rewardedAt;

	public boolean isRewarded() {
		return rewardedAt != null;
	}

	/** 성사 기한이 지났는가. 이미 성사된 초대는 지나지 않는다. */
	public boolean isExpiredAt(LocalDateTime now, int expireDays) {
		return rewardedAt == null && createdAt.plusDays(expireDays).isBefore(now);
	}
}
