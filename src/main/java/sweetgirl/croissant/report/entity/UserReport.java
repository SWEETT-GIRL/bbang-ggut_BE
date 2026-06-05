package sweetgirl.croissant.report.entity;

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
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.report.enums.ReportReasonType;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;
import sweetgirl.croissant.report.enums.UserReportStatus;

@Entity
@Table(name = "user_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reporter_id", nullable = false)
	private Member reporter;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 30)
	private ReportTargetType targetType;

	@Column(name = "target_id", nullable = false)
	private Long targetId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reported_member_id", nullable = false)
	private Member reportedMember;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_type", nullable = false, length = 30)
	private ReportReasonType reasonType;

	@Column(name = "reason_detail", length = 500)
	private String reasonDetail;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserReportStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "moderation_action", nullable = false, length = 30)
	private UserReportAction moderationAction;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public UserReport(
			Member reporter,
			ReportTargetType targetType,
			Long targetId,
			Member reportedMember,
			ReportReasonType reasonType,
			String reasonDetail) {
		this.reporter = reporter;
		this.targetType = targetType;
		this.targetId = targetId;
		this.reportedMember = reportedMember;
		this.reasonType = reasonType;
		this.reasonDetail = reasonDetail;
		this.status = UserReportStatus.PENDING;
		this.moderationAction = UserReportAction.NONE;
	}

	public void accept() {
		this.status = UserReportStatus.ACCEPTED;
		this.reviewedAt = LocalDateTime.now();
	}

	public void reject() {
		this.status = UserReportStatus.REJECTED;
		this.reviewedAt = LocalDateTime.now();
	}

	public void applyModerationAction(UserReportAction action) {
		this.moderationAction = action;
	}
}
