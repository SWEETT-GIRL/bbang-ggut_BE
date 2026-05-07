package sweetgirl.croissant.store.entity;

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
import sweetgirl.croissant.common.enums.StoreReportStatus;
import sweetgirl.croissant.common.enums.StoreReportType;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.entity.Menu;

@Entity
@Table(name = "store_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_id")
	private Menu menu;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StoreReportType requestType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StoreReportStatus status;

	@Column(nullable = false, length = 255)
	private String message;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public StoreReport(Member member, Store store, Menu menu,
		StoreReportType requestType, String message) {
		this.member = member;
		this.store = store;
		this.menu = menu;
		this.requestType = requestType;
		this.message = message;
		this.status = StoreReportStatus.PENDING;
	}

	public void review(StoreReportStatus status) {
		this.status = status;
		this.reviewedAt = LocalDateTime.now();
	}
}
