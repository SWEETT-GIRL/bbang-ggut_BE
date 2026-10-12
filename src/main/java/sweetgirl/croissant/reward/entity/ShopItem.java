package sweetgirl.croissant.reward.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.reward.enums.ShopItemKind;

/** 상점 품목. 가격은 늘 이 표의 값으로 뺀다 — 앱이 보낸 가격을 믿지 않는다. */
@Entity
@Table(name = "shop_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopItem {

	@Id
	@Column(length = 40)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private ShopItemKind kind;

	/** 모습(POSE)일 때 그림의 포즈 이름. {@code world_character_assets.pose} 와 같은 값이다. */
	@Column(length = 20)
	private String pose;

	@Column(nullable = false, length = 30)
	private String name;

	@Column(nullable = false)
	private int price;

	/** 이 레벨부터 살 수 있다. 없으면 누구나 산다. */
	@JdbcTypeCode(SqlTypes.SMALLINT)
	private Integer lockLevel;

	@Column(nullable = false)
	private int sortOrder;

	@Column(name = "is_new", nullable = false)
	private boolean isNew;

	/** 끄면 목록에서 빠진다. 이미 산 사람은 그대로 쓴다. */
	@Column(nullable = false)
	private boolean active;

	private ShopItem(String code, ShopItemKind kind) {
		this.code = code;
		this.kind = kind;
		this.active = true;
	}

	public static ShopItem create(String code, ShopItemKind kind) {
		return new ShopItem(code, kind);
	}

	public void revise(String pose, String name, int price, Integer lockLevel, int sortOrder, boolean isNew,
			boolean active) {
		this.pose = pose;
		this.name = name;
		this.price = price;
		this.lockLevel = lockLevel;
		this.sortOrder = sortOrder;
		this.isNew = isNew;
		this.active = active;
	}

	public boolean isPose() {
		return kind == ShopItemKind.POSE;
	}
}
