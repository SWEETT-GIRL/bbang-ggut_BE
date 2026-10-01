package sweetgirl.croissant.breadranking.enums;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.menu.enums.MenuKind;

/**
 * 빵 대분류 8개(10/1 확정) — 사용자는 보지 않는 내부 값이다. 쓰는 곳은 둘: 평가 칩을 앞에 보여 주는 순서, 썸네일.
 * 기존 {@link sweetgirl.croissant.menu.enums.MenuCategory}(21) 는 트렌드·분류 배치가 쓰고 있어 그대로 두고 랭킹용만 따로 둔다.
 *
 * <p>칩은 공통 목록 하나({@link TextureChip}·{@link TasteChip})이고 그룹은 앞 6개 순서만 정한다 — 젤라또가 쫀득할 수도 있어서
 * 묶음별로 가두지 않는다. 세분류가 빵·기타(그룹 없음)면 기본 순서(전체 빈도순)다.
 */
@Getter
@RequiredArgsConstructor
public enum BreadGroup {
	빵("빵",
			Set.of(MenuKind.소금빵, MenuKind.식빵, MenuKind.바게트, MenuKind.치아바타, MenuKind.포카치아, MenuKind.사워도우,
				MenuKind.깜빠뉴, MenuKind.통밀빵, MenuKind.브리오슈, MenuKind.모닝빵, MenuKind.번, MenuKind.롤, MenuKind.크림빵,
				MenuKind.단팥빵, MenuKind.소보로, MenuKind.앙버터, MenuKind.고구마빵, MenuKind.밤빵, MenuKind.소라빵,
				MenuKind.땅콩빵, MenuKind.꿀빵, MenuKind.커피콩빵, MenuKind.맘모스, MenuKind.맘모롤, MenuKind.쫀득빵,
				MenuKind.소세지빵, MenuKind.마늘빵, MenuKind.고로케, MenuKind.크로크무슈, MenuKind.오믈렛),
			List.of(TextureChip.겉바속촉, TextureChip.쫄깃, TextureChip.폭신, TextureChip.촉촉, TextureChip.바삭, TextureChip.쫀득),
			List.of(TasteChip.짭짤, TasteChip.고소, TasteChip.버터향, TasteChip.담백, TasteChip.달달, TasteChip.진함)),
	크루아상_페이스트리("크루아상·페이스트리",
			Set.of(MenuKind.크루아상, MenuKind.퀸아망, MenuKind.크로플, MenuKind.크룽지, MenuKind.페이스트리, MenuKind.파이,
				MenuKind.에끌레어, MenuKind.슈),
			List.of(TextureChip.바삭, TextureChip.겹겹, TextureChip.촉촉, TextureChip.겉바속촉, TextureChip.쫄깃, TextureChip.부드러움),
			List.of(TasteChip.버터향, TasteChip.고소, TasteChip.달달, TasteChip.짭짤, TasteChip.담백, TasteChip.진함)),
	베이글_도넛("베이글·도넛",
			Set.of(MenuKind.베이글, MenuKind.샌드베이글, MenuKind.도넛, MenuKind.츄러스, MenuKind.프레첼, MenuKind.와플),
			List.of(TextureChip.쫄깃, TextureChip.폭신, TextureChip.바삭, TextureChip.촉촉, TextureChip.쫀득, TextureChip.부드러움),
			List.of(TasteChip.담백, TasteChip.달달, TasteChip.짭짤, TasteChip.고소, TasteChip.버터향, TasteChip.진함)),
	샌드위치_토스트("샌드위치·토스트",
			Set.of(MenuKind.샌드위치, MenuKind.토스트, MenuKind.산도),
			List.of(TextureChip.바삭, TextureChip.촉촉, TextureChip.부드러움, TextureChip.쫄깃, TextureChip.폭신, TextureChip.묵직),
			List.of(TasteChip.짭짤, TasteChip.고소, TasteChip.담백, TasteChip.안느끼함, TasteChip.달달, TasteChip.진함)),
	케이크_디저트("케이크·디저트",
			Set.of(MenuKind.케이크, MenuKind.티라미수, MenuKind.파운드, MenuKind.프레지에, MenuKind.떠먹케, MenuKind.몽블랑,
				MenuKind.카스테라, MenuKind.수플레, MenuKind.크레페, MenuKind.타르트, MenuKind.에그타르트, MenuKind.푸딩,
				MenuKind.브륄레, MenuKind.초콜릿, MenuKind.마카롱),
			List.of(TextureChip.부드러움, TextureChip.촉촉, TextureChip.꾸덕, TextureChip.쫀득, TextureChip.가벼움, TextureChip.묵직),
			List.of(TasteChip.달달, TasteChip.상큼, TasteChip.진함, TasteChip.고소, TasteChip.안느끼함, TasteChip.버터향)),
	쿠키_구움과자("쿠키·구움과자",
			Set.of(MenuKind.휘낭시에, MenuKind.마들렌, MenuKind.까눌레, MenuKind.스콘, MenuKind.쿠키, MenuKind.쫀득쿠키,
				MenuKind.브라우니, MenuKind.러스크, MenuKind.버터바, MenuKind.비스킷, MenuKind.티그레, MenuKind.머핀,
				MenuKind.다쿠아즈, MenuKind.호두과자, MenuKind.오란다),
			List.of(TextureChip.쫀득, TextureChip.바삭, TextureChip.꾸덕, TextureChip.촉촉, TextureChip.부드러움, TextureChip.겹겹),
			List.of(TasteChip.달달, TasteChip.고소, TasteChip.버터향, TasteChip.짭짤, TasteChip.진함, TasteChip.담백)),
	떡_한과("떡·한과",
			Set.of(MenuKind.떡, MenuKind.버터떡, MenuKind.약과, MenuKind.화과자, MenuKind.모나카, MenuKind.만쥬, MenuKind.한과,
				MenuKind.붕어빵, MenuKind.계란빵),
			List.of(TextureChip.쫀득, TextureChip.쫄깃, TextureChip.부드러움, TextureChip.바삭, TextureChip.촉촉, TextureChip.묵직),
			List.of(TasteChip.달달, TasteChip.고소, TasteChip.담백, TasteChip.짭짤, TasteChip.진함, TasteChip.상큼)),
	빙수_아이스("빙수·아이스",
			Set.of(MenuKind.빙수, MenuKind.아이스크림, MenuKind.요거트),
			List.of(TextureChip.부드러움, TextureChip.시원, TextureChip.쫀득, TextureChip.가벼움, TextureChip.꾸덕,
				TextureChip.사각사각),
			List.of(TasteChip.달달, TasteChip.상큼, TasteChip.진함, TasteChip.고소, TasteChip.안느끼함, TasteChip.담백));

	private final String label;
	private final Set<MenuKind> kinds;
	/** 이 그룹에서 앞에 펼쳐 보이는 식감 6개(순서) — 나머지는 "더보기"에 기본 순서로 */
	private final List<TextureChip> leadingTextures;
	private final List<TasteChip> leadingTastes;

	/** 빵·기타처럼 어느 그룹도 아닌 세분류는 empty — 기본 순서를 쓴다. */
	public static Optional<BreadGroup> of(MenuKind kind) {
		return Arrays.stream(values())
			.filter(group -> group.kinds.contains(kind))
			.findFirst();
	}

	public static List<TextureChip> defaultTextureOrder() {
		return List.of(TextureChip.values());
	}

	public static List<TasteChip> defaultTasteOrder() {
		return List.of(TasteChip.values());
	}

	/** 앞 6개 뒤에 나머지를 기본 순서로 — 전체 16개가 다 들어간다. */
	public List<TextureChip> textureOrder() {
		return order(leadingTextures, TextureChip.values());
	}

	public List<TasteChip> tasteOrder() {
		return order(leadingTastes, TasteChip.values());
	}

	private static <T> List<T> order(List<T> leading, T[] all) {
		List<T> ordered = new ArrayList<>(leading);
		for (T chip : all) {
			if (!ordered.contains(chip)) {
				ordered.add(chip);
			}
		}
		return List.copyOf(ordered);
	}
}
