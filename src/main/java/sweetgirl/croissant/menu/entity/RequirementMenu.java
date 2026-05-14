package sweetgirl.croissant.menu.entity;

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
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "requirement_menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequirementMenu extends BaseTimeEntity {

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

	@Builder
	public RequirementMenu(Store store, Member member, MenuCategory menuCategory,
			String name, Integer price, String description) {
		this.store = store;
		this.member = member;
		this.menuCategory = menuCategory;
		this.name = name;
		this.price = price;
		this.description = description;
	}
}
