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
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;
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
	@JoinColumn(name = "menu_category_id")
	private MenuCategory menuCategory;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 100)
	private String normalizedName;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_member_id")
	private Member createdByMember;

	@Column
	private Integer price;

	@Column(nullable = false)
	private int likeCount;

	@Column
	private LocalDateTime deletedAt;

	@Column(length = 255)
	private String description;

	@Column(name = "display_order")
	private Integer displayOrder;

	/** AI 분류 결과 — 빵집 먹거리인가. null = 미판정 (기존 데이터·분류 전) */
	@Column(name = "is_bakery_item")
	private Boolean bakeryItem;

	/** AI 가 분류를 시도한 시각. 답이 갈려 카테고리를 못 받아도 찍는다 — 같은 행을 무한 재시도하지 않게 */
	@Column
	private LocalDateTime classifiedAt;

	@Builder
	public Menu(
			Store store,
			MenuCategory menuCategory,
			String name,
			Integer price,
			String description,
			Member createdByMember) {
		this.store = store;
		this.menuCategory = menuCategory;
		this.name = name;
		this.normalizedName = MenuNameNormalizer.normalize(name);
		this.price = price;
		this.description = description;
		this.createdByMember = createdByMember;
		this.likeCount = 0;
	}

	public void update(String name, Integer price, String description, MenuCategory menuCategory) {
		if (name != null) {
			this.name = name;
			this.normalizedName = MenuNameNormalizer.normalize(name);
		}
		this.price = price;
		if (description != null) {
			this.description = description;
		}
		if (menuCategory != null) {
			this.menuCategory = menuCategory;
		}
	}

	/** 공동 가격 수정 — 빵 평가 화면에서 누구나. 이력은 서비스가 남긴다. */
	public void updatePrice(int price) {
		this.price = price;
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

	/** AI 분류 확정 — 세 번 물어 같은 kind 가 나왔을 때 */
	public void applyClassification(MenuCategory menuCategory, boolean bakeryItem) {
		this.menuCategory = menuCategory;
		this.bakeryItem = bakeryItem;
		this.classifiedAt = LocalDateTime.now();
	}

	/** AI 가 시도했지만 답을 못 정함 — 카테고리는 비워 두고 시도 흔적만 남긴다 (검수 대상) */
	public void markClassificationAttempted() {
		this.classifiedAt = LocalDateTime.now();
	}

	public void updateOrder(Integer displayOrder) {
		this.displayOrder = displayOrder;
	}
}
