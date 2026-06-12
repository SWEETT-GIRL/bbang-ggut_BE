package sweetgirl.croissant.contribution.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreReportStatus;

@Entity
@Table(name = "menu_contribution_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuContributionReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "contribution_report_id", nullable = false)
	private ContributionReport contributionReport;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_id")
	private Menu menu;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_category_id")
	private MenuCategory menuCategory;

	@Column(name = "request_type", nullable = false, length = 30)
	private String requestType;

	@Column(length = 100)
	private String name;

	@Column
	private Integer price;

	@Column(length = 500)
	private String description;

	@Column(columnDefinition = "TEXT")
	private String message;

	@Column(nullable = false, length = 20)
	private StoreReportStatus status;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public MenuContributionReport(
			ContributionReport contributionReport,
			Store store,
			Menu menu,
			MenuCategory menuCategory,
			String requestType,
			String name,
			Integer price,
			String description,
			String message) {
		this.contributionReport = contributionReport;
		this.store = store;
		this.menu = menu;
		this.menuCategory = menuCategory;
		this.requestType = requestType;
		this.name = name;
		this.price = price;
		this.description = description;
		this.message = message;
		this.status = StoreReportStatus.PENDING;
	}

	public Member getMember() {
		return contributionReport.getMember();
	}

	public Menu toMenu() {
		return new Menu(store, menuCategory, name, price, description);
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
