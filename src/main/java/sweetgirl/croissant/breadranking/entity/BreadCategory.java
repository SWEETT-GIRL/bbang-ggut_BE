package sweetgirl.croissant.breadranking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;

/**
 * 빵 종류(세분류) — 공용 풀. 기본 목록은 {@link MenuKind}(빵·기타 뺀 89종)에서 마이그레이션으로 채우고,
 * 사용자가 직접 만든 것("트러플 소금빵")도 같은 표에 들어가 모두의 "빵 추가" 목록에 뜬다.
 *
 * <p>같은 이름은 하나로 — {@link #nameKey} 는 {@link MenuNameNormalizer} 규칙(영숫자·한글만, 소문자)이라
 * "트러플 소금빵"과 "트러플소금빵"이 한 행이다.
 */
@Entity
@Table(name = "bread_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadCategory extends BaseTimeEntity {

	public static final int NAME_MIN_LENGTH = 2;
	public static final int NAME_MAX_LENGTH = 15;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 30)
	private String name;

	@Column(nullable = false, length = 30)
	private String nameKey;

	/** 기본 목록이면 그 MenuKind — 빵로그 태그의 breadCategory(MenuKind 이름)와 이 값으로 잇는다. 사용자 생성은 null. */
	@Enumerated(EnumType.STRING)
	@Column(length = 50)
	private MenuKind menuKind;

	/** 대분류 — 칩 순서·썸네일용. 분류기가 못 맞춘 사용자 생성은 null(기본 순서). */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private BreadGroup breadGroup;

	/** 만든 회원. 기본 목록은 null. 연관 관계로 잡지 않는 이유는 MemberWorld 와 같다 — id 만 있으면 된다. */
	private Long createdBy;

	private BreadCategory(String name, MenuKind menuKind, BreadGroup breadGroup, Long createdBy) {
		this.name = name;
		this.nameKey = MenuNameNormalizer.normalize(name);
		this.menuKind = menuKind;
		this.breadGroup = breadGroup;
		this.createdBy = createdBy;
	}

	/** 사용자가 직접 만든 세분류. 이름 검증·중복은 서비스가 먼저 본다. */
	public static BreadCategory custom(String name, BreadGroup breadGroup, Long createdBy) {
		return new BreadCategory(name, null, breadGroup, createdBy);
	}

	/** 기본 목록 한 줄 — 마이그레이션 시딩과 같은 모양. 새 MenuKind 가 생기면 기동 때 채운다. */
	public static BreadCategory defaultOf(MenuKind kind, BreadGroup breadGroup) {
		return new BreadCategory(kind.name(), kind, breadGroup, null);
	}

	public boolean isDefault() {
		return menuKind != null;
	}
}
