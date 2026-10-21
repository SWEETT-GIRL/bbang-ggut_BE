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
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "abuse_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbuseReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reporter_id", nullable = false)
	private Member reporter;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 30)
	private AbuseReportTargetType targetType;

	@Column(name = "target_id", nullable = false)
	private Long targetId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reported_member_id", nullable = false)
	private Member reportedMember;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_type", nullable = false, length = 30)
	private AbuseReportReasonType reasonType;

	@Column(name = "reason_detail", length = 500)
	private String reasonDetail;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AbuseReportStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "moderation_action", nullable = false, length = 30)
	private AbuseReportAction moderationAction;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public AbuseReport(
			Member reporter,
			AbuseReportTargetType targetType,
			Long targetId,
			Member reportedMember,
			AbuseReportReasonType reasonType,
			String reasonDetail) {
		this.reporter = reporter;
		this.targetType = targetType;
		this.targetId = targetId;
		this.reportedMember = reportedMember;
		this.reasonType = reasonType;
		this.reasonDetail = reasonDetail;
		this.status = AbuseReportStatus.PENDING;
		this.moderationAction = AbuseReportAction.NONE;
	}

	public void accept() {
		this.status = AbuseReportStatus.ACCEPTED;
		this.reviewedAt = LocalDateTime.now();
	}

	public void reject() {
		this.status = AbuseReportStatus.REJECTED;
		this.reviewedAt = LocalDateTime.now();
	}

	public void applyModerationAction(AbuseReportAction action) {
		this.moderationAction = action;
	}
}
