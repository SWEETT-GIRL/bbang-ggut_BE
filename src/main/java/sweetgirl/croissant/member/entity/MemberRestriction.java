package sweetgirl.croissant.member.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.member.enums.MemberRestrictionType;

@Entity
@Table(name = "member_restriction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberRestriction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(name = "restriction_type", nullable = false, length = 30)
	private MemberRestrictionType restrictionType;

	@Column(length = 500)
	private String reason;

	@Column(name = "starts_at", nullable = false)
	private LocalDateTime startsAt;

	@Column(name = "ends_at")
	private LocalDateTime endsAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	public MemberRestriction(
			Member member,
			MemberRestrictionType restrictionType,
			String reason,
			LocalDateTime startsAt,
			LocalDateTime endsAt) {
		this.member = member;
		this.restrictionType = restrictionType;
		this.reason = reason;
		this.startsAt = startsAt != null ? startsAt : LocalDateTime.now();
		this.endsAt = endsAt;
		this.createdAt = LocalDateTime.now();
	}
}
