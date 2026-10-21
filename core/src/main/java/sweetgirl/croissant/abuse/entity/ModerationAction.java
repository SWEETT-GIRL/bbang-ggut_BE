package sweetgirl.croissant.abuse.entity;

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
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "moderation_action")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModerationAction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "report_id")
	private AbuseReport report;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 30)
	private ModerationTargetType targetType;

	@Column(name = "target_id", nullable = false)
	private Long targetId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AbuseReportAction action;

	@Column(length = 500)
	private String reason;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	public ModerationAction(
			AbuseReport report,
			ModerationTargetType targetType,
			Long targetId,
			Member member,
			AbuseReportAction action,
			String reason) {
		this.report = report;
		this.targetType = targetType;
		this.targetId = targetId;
		this.member = member;
		this.action = action;
		this.reason = reason;
		this.createdAt = LocalDateTime.now();
	}
}
