package sweetgirl.croissant.menu.entity;

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
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreReportStatus;

@Entity
@Table(name = "menu_add_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuAddReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_category_id", nullable = false)
	private MenuCategory menuCategory;

	@Column(nullable = false, length = 100)
	private String name;

	@Column
	private Integer price;

	@Column(length = 500)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StoreReportStatus status;

	@Column
	private LocalDateTime reviewedAt;

	@Builder
	public MenuAddReport(Store store, Member member, MenuCategory menuCategory,
			String name, Integer price, String description) {
		this.store = store;
		this.member = member;
		this.menuCategory = menuCategory;
		this.name = name;
		this.price = price;
		this.description = description;
		this.status = StoreReportStatus.PENDING;
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
