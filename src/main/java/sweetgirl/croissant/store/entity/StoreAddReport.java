package sweetgirl.croissant.store.entity;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.enums.StoreReportStatus;

@Entity
@Table(name = "store_add_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreAddReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(name = "naver_place_id", length = 50)
	private String naverPlaceId;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 255)
	private String address;

	@Column(name = "road_address", length = 255)
	private String roadAddress;

	@Column(length = 20)
	private String phone;

	@Column(nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(length = 500)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StoreReportStatus status;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public StoreAddReport(Member member, String naverPlaceId, String name, String address, String roadAddress,
			String phone, Point location, String reason) {
		this.member = member;
		this.naverPlaceId = naverPlaceId;
		this.name = name;
		this.address = address;
		this.roadAddress = roadAddress;
		this.phone = phone;
		this.location = location;
		this.reason = reason;
		this.status = StoreReportStatus.PENDING;
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
