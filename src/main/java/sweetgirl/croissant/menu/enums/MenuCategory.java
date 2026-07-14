package sweetgirl.croissant.menu.enums;

import java.util.Set;

// 랭킹용 대분류(카테고리). 각 카테고리는 자신에 속하는 개별 빵 종류(MenuKind) 집합을 가진다.
// 트렌드 랭킹에서 가게의 대표 메뉴 사진을 고를 때, 이 매핑으로 카테고리에 해당하는 메뉴를 판별한다.
public enum MenuCategory {
	하드빵(MenuKind.식빵, MenuKind.바게트, MenuKind.치아바타, MenuKind.포카치아, MenuKind.사워도우, MenuKind.깜빠뉴, MenuKind.토스트),
	크루아상(MenuKind.크루아상, MenuKind.퀸아망, MenuKind.크로플),
	페이스트리(MenuKind.페이스트리),
	소금빵(MenuKind.소금빵),
	베이글(MenuKind.베이글, MenuKind.샌드베이글),
	맘모스(MenuKind.맘모스, MenuKind.맘모롤),
	쫀득빵(MenuKind.쫀득빵),
	조리빵(MenuKind.소세지빵, MenuKind.마늘빵, MenuKind.크로크무슈, MenuKind.고로케, MenuKind.프레첼,
			MenuKind.소보로, MenuKind.앙버터, MenuKind.크림빵, MenuKind.슈, MenuKind.브리오슈,
			MenuKind.고구마빵, MenuKind.밤빵, MenuKind.번, MenuKind.버터떡),
	샌드위치(MenuKind.샌드위치),
	케이크(MenuKind.케이크, MenuKind.티라미수, MenuKind.프레지에, MenuKind.몽블랑, MenuKind.떠먹케,
			MenuKind.파운드, MenuKind.에끌레어, MenuKind.롤),
	타르트(MenuKind.에그타르트, MenuKind.타르트),
	파이(MenuKind.파이),
	구움과자(MenuKind.휘낭시에, MenuKind.마들렌, MenuKind.까눌레, MenuKind.쿠키, MenuKind.쫀득쿠키, MenuKind.브라우니,
			MenuKind.러스크, MenuKind.스콘, MenuKind.비스킷, MenuKind.티그레, MenuKind.버터바, MenuKind.와플),
	도넛(MenuKind.도넛),
	마카롱(MenuKind.마카롱),
	빙수(MenuKind.빙수),
	푸딩(MenuKind.푸딩, MenuKind.브륄레);

	private final Set<MenuKind> menuKinds;

	MenuCategory(MenuKind... menuKinds) {
		this.menuKinds = Set.of(menuKinds);
	}

	public Set<MenuKind> getMenuKinds() {
		return menuKinds;
	}
}
