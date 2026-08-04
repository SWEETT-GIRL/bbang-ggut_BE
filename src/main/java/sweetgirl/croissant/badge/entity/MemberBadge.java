package sweetgirl.croissant.badge.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "member_badges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberBadge {

	@EmbeddedId
	private MemberBadgeId id;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@MapsId("badgeId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "badge_id", nullable = false)
	private Badge badge;

	@Column(nullable = false)
	private LocalDateTime achievedAt;

	private LocalDateTime celebratedAt;

	private MemberBadge(Member member, Badge badge, LocalDateTime achievedAt) {
		this.id = new MemberBadgeId(member.getId(), badge.getId());
		this.member = member;
		this.badge = badge;
		this.achievedAt = achievedAt;
	}

	public static MemberBadge achieved(Member member, Badge badge, LocalDateTime achievedAt) {
		return new MemberBadge(member, badge, achievedAt);
	}

	public void celebrate(LocalDateTime celebratedAt) {
		if (this.celebratedAt == null) {
			this.celebratedAt = celebratedAt;
		}
	}
}
