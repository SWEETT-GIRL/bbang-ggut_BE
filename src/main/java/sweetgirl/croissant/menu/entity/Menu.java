package sweetgirl.croissant.menu.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_category_id", nullable = false)
	private MenuCategory menuCategory;

	@Column(nullable = false, length = 100)
	private String name;

	@Column
	private Integer price;

	@Column(nullable = false)
	private int recommendCount;

	@Column
	private LocalDateTime deletedAt;

	@Column(length = 255)
	private String description;

	@Column(name = "display_order")
	private Integer displayOrder;

	@Builder
	public Menu(Store store, MenuCategory menuCategory, String name, Integer price, String description) {
		this.store = store;
		this.menuCategory = menuCategory;
		this.name = name;
		this.price = price;
		this.description = description;
		this.recommendCount = 0;
	}

	public void update(String name, Integer price, String description, MenuCategory menuCategory) {
		if (name != null) {
			this.name = name;
		}
		this.price = price;
		if (description != null) {
			this.description = description;
		}
		if (menuCategory != null) {
			this.menuCategory = menuCategory;
		}
	}

	// 크롤러 재실행(match-and-enrich) 시 비어 있는 필드만 채운다. 어드민/기존 값은 덮어쓰지 않음.
	// LocalData 로 들어온 한글 메뉴의 빈 가격·설명을 네이버 크롤 값으로 보강하는 용도.
	public void enrichIfMissing(Integer price, String description) {
		if (this.price == null && price != null) {
			this.price = price;
		}
		if ((this.description == null || this.description.isBlank())
			&& description != null && !description.isBlank()) {
			this.description = description;
		}
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public void updateOrder(Integer displayOrder) {
		this.displayOrder = displayOrder;
	}
}
