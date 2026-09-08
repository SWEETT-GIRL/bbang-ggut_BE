package sweetgirl.croissant.menu.enums;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 트렌드(랭킹) 대분류 카테고리.
 * 각 대분류는 메뉴의 세분류({@link MenuKind}) 집합과 매핑된다 —
 * 트렌드 썸네일에서 "해당 카테고리의 메뉴 사진"을 고를 때 사용.
 *
 * '빵'·'기타'를 뺀 모든 MenuKind 는 정확히 하나의 대분류에 속해야 한다 — 어느 대분류에도 없으면
 * 그 kind 의 메뉴 사진은 어떤 랭킹 카테고리의 썸네일로도 뽑히지 않는다 (2026-09-07 실측: 식빵 13,037건).
 * 랭킹 자체(어느 가게가 어느 카테고리에 오르는지)와 카테고리 목록은 별도 배치(home_recommand)가
 * 자기 그룹표로 정한다 — 여기 대분류를 늘려도 트렌드 카테고리는 늘지 않고, 배치 그룹표에 같은 이름이 생겨야 한다.
 * {@code MenuCategoryTest} 가 이 불변식을 지킨다.
 */
public enum MenuCategory {
	하드빵(Set.of(MenuKind.바게트, MenuKind.치아바타, MenuKind.포카치아, MenuKind.사워도우, MenuKind.깜빠뉴, MenuKind.프레첼,
		MenuKind.통밀빵, MenuKind.브리오슈)),
	크루아상(Set.of(MenuKind.크루아상, MenuKind.퀸아망, MenuKind.크로플, MenuKind.크룽지)),
	페이스트리(Set.of(MenuKind.페이스트리, MenuKind.에끌레어, MenuKind.슈)),
	소금빵(Set.of(MenuKind.소금빵)),
	베이글(Set.of(MenuKind.베이글, MenuKind.샌드베이글)),
	맘모스(Set.of(MenuKind.맘모스, MenuKind.맘모롤)),
	쫀득빵(Set.of(MenuKind.쫀득빵)),
	조리빵(Set.of(MenuKind.소세지빵, MenuKind.마늘빵, MenuKind.고로케, MenuKind.번, MenuKind.롤, MenuKind.모닝빵)),
	샌드위치(Set.of(MenuKind.샌드위치, MenuKind.토스트, MenuKind.크로크무슈)),
	케이크(Set.of(MenuKind.케이크, MenuKind.티라미수, MenuKind.파운드, MenuKind.프레지에, MenuKind.떠먹케, MenuKind.몽블랑,
		MenuKind.카스테라, MenuKind.수플레, MenuKind.오믈렛, MenuKind.산도, MenuKind.크레페)),
	타르트(Set.of(MenuKind.타르트, MenuKind.에그타르트)),
	파이(Set.of(MenuKind.파이)),
	구움과자(Set.of(MenuKind.스콘, MenuKind.휘낭시에, MenuKind.마들렌, MenuKind.까눌레, MenuKind.쿠키, MenuKind.브라우니,
		MenuKind.러스크, MenuKind.쫀득쿠키, MenuKind.버터바, MenuKind.비스킷, MenuKind.티그레,
		MenuKind.머핀, MenuKind.다쿠아즈, MenuKind.호두과자, MenuKind.오란다)),
	도넛(Set.of(MenuKind.도넛, MenuKind.츄러스, MenuKind.와플)),
	마카롱(Set.of(MenuKind.마카롱)),
	빙수(Set.of(MenuKind.빙수, MenuKind.아이스크림, MenuKind.요거트)),
	푸딩(Set.of(MenuKind.푸딩, MenuKind.브륄레)),
	// 2026-09-07 신설 — 그전까지 어느 대분류에도 없던 kind 들의 자리
	식빵(Set.of(MenuKind.식빵)),
	단과자빵(Set.of(MenuKind.크림빵, MenuKind.단팥빵, MenuKind.소보로, MenuKind.앙버터, MenuKind.고구마빵, MenuKind.밤빵,
		MenuKind.소라빵, MenuKind.땅콩빵, MenuKind.꿀빵, MenuKind.붕어빵, MenuKind.계란빵, MenuKind.커피콩빵)),
	떡한과(Set.of(MenuKind.떡, MenuKind.버터떡, MenuKind.약과, MenuKind.화과자, MenuKind.모나카, MenuKind.만쥬,
		MenuKind.한과)),
	// 2026-09-08 신설 — 초콜릿은 기존 어느 대분류에도 맞지 않는다
	초콜릿(Set.of(MenuKind.초콜릿));

	/** 대분류 밖에 두는 kind — 분류 실패 폴백이라 트렌드 집계 대상이 아니다 */
	public static final Set<MenuKind> UNCATEGORIZED_KINDS = Set.of(MenuKind.빵, MenuKind.기타);

	private final Set<MenuKind> matchingKinds;

	MenuCategory(Set<MenuKind> matchingKinds) {
		this.matchingKinds = matchingKinds;
	}

	/** 세분류 메뉴 종류가 이 대분류에 속하는지 */
	public boolean matches(MenuKind kind) {
		return kind != null && matchingKinds.contains(kind);
	}

	/** 세분류가 속한 대분류. '빵'·'기타'는 empty */
	public static Optional<MenuCategory> of(MenuKind kind) {
		return Arrays.stream(values())
			.filter(category -> category.matches(kind))
			.findFirst();
	}
}
