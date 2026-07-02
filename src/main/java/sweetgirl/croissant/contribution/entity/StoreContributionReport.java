package sweetgirl.croissant.contribution.entity;

import java.time.LocalDateTime;

import org.locationtech.jts.geom.Point;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreReportStatus;

@Entity
@Table(name = "store_contribution_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreContributionReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "contribution_report_id", nullable = false)
	private ContributionReport contributionReport;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id")
	private Store store;

	@Column(name = "request_type", nullable = false, length = 30)
	private String requestType;

	@Column(name = "naver_place_id", length = 50)
	private String naverPlaceId;

	@Column(length = 100)
	private String name;

	@Column(length = 255)
	private String address;

	@Column(name = "road_address", length = 255)
	private String roadAddress;

	@Column(length = 20)
	private String phone;

	@Column(columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(columnDefinition = "TEXT")
	private String message;

	@Column(length = 500)
	private String reason;

	@Column(nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private StoreReportStatus status;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public StoreContributionReport(
			ContributionReport contributionReport,
			Store store,
			String requestType,
			String naverPlaceId,
			String name,
			String address,
			String roadAddress,
			String phone,
			Point location,
			String message,
			String reason) {
		this.contributionReport = contributionReport;
		this.store = store;
		this.requestType = requestType;
		this.naverPlaceId = naverPlaceId;
		this.name = name;
		this.address = address;
		this.roadAddress = roadAddress;
		this.phone = phone;
		this.location = location;
		this.message = message;
		this.reason = reason;
		this.status = StoreReportStatus.PENDING;
	}

	public Member getMember() {
		return contributionReport.getMember();
	}

	public void approve() {
		this.status = StoreReportStatus.ACCEPTED;
		this.reviewedAt = LocalDateTime.now();
	}

	public void reject() {
		this.status = StoreReportStatus.REJECTED;
		this.reviewedAt = LocalDateTime.now();
	}
}
