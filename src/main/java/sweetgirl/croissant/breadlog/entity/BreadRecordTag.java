package sweetgirl.croissant.breadlog.entity;

import java.util.Set;
import java.util.stream.Collectors;

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
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

@Entity
@Table(name = "bread_record_tag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecordTag extends BaseTimeEntity {

	/** 룰이 여기까지 물러섰으면 "모르겠다"와 같다 — 그때만 메뉴의 분류를 본다 */
	private static final Set<String> FALLBACK_CATEGORIES = MenuCategory.UNCATEGORIZED_KINDS.stream()
		.map(Enum::name)
		.collect(Collectors.toUnmodifiableSet());

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "record_id", nullable = false)
	private BreadRecord record;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "photo_id", nullable = false)
	private BreadRecordPhoto photo;

	@Column(nullable = false, length = 100)
	private String name;

	/**
	 * 저장 시점 룰 판정. 메뉴를 못 찾은 태그에겐 이게 유일한 답이고, 룰이 구체 kind 를 낸 태그도 이 값을 쓴다.
	 * 룰이 '빵'·'기타'로 물러섰을 때만 {@link #menu} 의 분류가 이를 대신한다 — {@link #resolvedCategory()}
	 */
	@Column(nullable = false, length = 50)
	private String breadCategory;

	/**
	 * 이 태그가 가리키는 가게 메뉴. 가게를 안 고른 기록이나 그 가게에 없는 이름이면 null.
	 * 값을 베껴 두지 않고 가리키기만 하므로, 메뉴가 나중에 AI·어드민으로 분류돼도 태그가 따라온다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_id")
	private Menu menu;

	/**
	 * 이 자리에 AI 가 제안했던 메뉴. {@link #menu} 와 같으면 제안을 그대로 받아들인 것이고,
	 * 다르면 유저가 고친 것이다 — 자주 틀리는 쌍이 여기서 나온다.
	 *
	 * <p>제안이 없던 태그는 null 이다: 유저가 직접 단 것, 기능이 꺼져 있던 때, 구버전 앱.
	 * 쌓아 두기만 하고 지금은 아무 데도 쓰지 않는다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "suggested_menu_id")
	private Menu suggestedMenu;

	@Column(name = "x", nullable = false)
	private double positionX;

	@Column(name = "y", nullable = false)
	private double positionY;

	/** 이름표 꾸밈("모양.종이[.up]"). 앱이 정한 이름을 그대로 든다 — 서버는 해석하지 않는다. 없으면 기본 */
	@Column(length = 50)
	private String style;

	@Builder
	public BreadRecordTag(
			BreadRecord record,
			BreadRecordPhoto photo,
			Menu menu,
			Menu suggestedMenu,
			String name,
			String breadCategory,
			double positionX,
			double positionY,
			String style) {
		this.record = record;
		this.photo = photo;
		this.menu = menu;
		this.suggestedMenu = suggestedMenu;
		this.name = name;
		this.breadCategory = breadCategory;
		this.positionX = positionX;
		this.positionY = positionY;
		this.style = style;
	}

	/**
	 * 화면에 보일 분류. 룰이 낸 값이 먼저고, 룰이 '빵'·'기타'로 물러섰을 때만 메뉴의 분류를 본다.
	 *
	 * <p>순서가 이런 이유 — 룰은 "마지막 제품 타입 단어"라는 한 원리로 결정되고(밤식빵은 식빵,
	 * 앙버터 치아바타는 치아바타), 메뉴 분류는 이름 전체의 뜻을 보는 다른 원리다. 둘을 섞으면
	 * 같은 표를 두고도 답이 갈린다. 그래서 룰이 답을 낸 자리는 건드리지 않고, 룰이 모른다고
	 * 물러선 자리만 메뉴에게 묻는다.
	 *
	 * <p>메뉴 쪽도 '빵'·'기타'면 그것 역시 "모르겠다"이므로 룰이 낸 값을 그대로 둔다.
	 */
	public String resolvedCategory() {
		MenuKind kind = menu == null || menu.getMenuCategory() == null ? null : menu.getMenuCategory().getName();
		return resolveCategory(breadCategory, kind);
	}

	/** 현재 태그와 월말 이력에서 동일한 분류 규칙을 사용한다. */
	public static String resolveCategory(String breadCategory, MenuKind kind) {
		if (!FALLBACK_CATEGORIES.contains(breadCategory) || kind == null) {
			return breadCategory;
		}
		return MenuCategory.UNCATEGORIZED_KINDS.contains(kind) ? breadCategory : kind.name();
	}

	/** 룰도 메뉴도 '빵'·'기타'에 머문 태그 — 무슨 빵인지 못 정한 것이라 집계에서 뺀다 */
	public boolean uncategorized() {
		return FALLBACK_CATEGORIES.contains(resolvedCategory());
	}

	/** 분류 규칙이 바뀌었을 때 기존 태그를 다시 분류 — 이름은 그대로, 분류만. 바뀌었으면 true */
	public boolean reclassify(String breadCategory) {
		if (this.breadCategory.equals(breadCategory)) {
			return false;
		}
		this.breadCategory = breadCategory;
		return true;
	}
}
