package sweetgirl.croissant.menu.enums;

import java.util.Set;

/**
 * 트렌드(랭킹) 대분류 카테고리.
 * 각 대분류는 메뉴의 세분류({@link MenuKind}) 집합과 매핑된다 —
 * 트렌드 썸네일에서 "해당 카테고리의 메뉴 사진"을 고를 때 사용.
 */
public enum MenuCategory {
	하드빵(Set.of(MenuKind.바게트, MenuKind.치아바타, MenuKind.포카치아, MenuKind.사워도우, MenuKind.깜빠뉴, MenuKind.프레첼)),
	크루아상(Set.of(MenuKind.크루아상, MenuKind.퀸아망, MenuKind.크로플)),
	페이스트리(Set.of(MenuKind.페이스트리, MenuKind.에끌레어, MenuKind.슈)),
	소금빵(Set.of(MenuKind.소금빵)),
	베이글(Set.of(MenuKind.베이글, MenuKind.샌드베이글)),
	맘모스(Set.of(MenuKind.맘모스, MenuKind.맘모롤)),
	쫀득빵(Set.of(MenuKind.쫀득빵)),
	조리빵(Set.of(MenuKind.소세지빵, MenuKind.마늘빵, MenuKind.고로케, MenuKind.크림빵)),
	샌드위치(Set.of(MenuKind.샌드위치, MenuKind.토스트, MenuKind.크로크무슈)),
	케이크(Set.of(MenuKind.케이크, MenuKind.티라미수, MenuKind.파운드, MenuKind.프레지에, MenuKind.떠먹케, MenuKind.몽블랑)),
	타르트(Set.of(MenuKind.타르트, MenuKind.에그타르트)),
	파이(Set.of(MenuKind.파이)),
	구움과자(Set.of(MenuKind.스콘, MenuKind.휘낭시에, MenuKind.마들렌, MenuKind.까눌레, MenuKind.쿠키, MenuKind.브라우니,
		MenuKind.러스크, MenuKind.쫀득쿠키, MenuKind.버터바, MenuKind.비스킷, MenuKind.티그레,
		MenuKind.머핀, MenuKind.다쿠아즈)),
	도넛(Set.of(MenuKind.도넛)),
	마카롱(Set.of(MenuKind.마카롱)),
	빙수(Set.of(MenuKind.빙수)),
	푸딩(Set.of(MenuKind.푸딩, MenuKind.브륄레));

	private final Set<MenuKind> matchingKinds;

	MenuCategory(Set<MenuKind> matchingKinds) {
		this.matchingKinds = matchingKinds;
	}

	/** 세분류 메뉴 종류가 이 대분류에 속하는지 */
	public boolean matches(MenuKind kind) {
		return kind != null && matchingKinds.contains(kind);
	}
}
