package sweetgirl.croissant.contribution.entity;

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
import sweetgirl.croissant.contribution.enums.ContributionReportStatus;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "contribution_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContributionReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 20)
	private ContributionTargetType targetType;

	@Enumerated(EnumType.STRING)
	@Column(name = "report_type", nullable = false, length = 20)
	private ContributionReportType reportType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ContributionReportStatus status;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public ContributionReport(
			Member member,
			ContributionTargetType targetType,
			ContributionReportType reportType) {
		this.member = member;
		this.targetType = targetType;
		this.reportType = reportType;
		this.status = ContributionReportStatus.PENDING;
	}

	public void approve() {
		this.status = ContributionReportStatus.ACCEPTED;
		this.reviewedAt = LocalDateTime.now();
	}

	public void reject() {
		this.status = ContributionReportStatus.REJECTED;
		this.reviewedAt = LocalDateTime.now();
	}
}
