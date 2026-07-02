package sweetgirl.croissant.external.opensearch.query;

import static sweetgirl.croissant.external.opensearch.document.StoreDocumentConstants.*;
import static sweetgirl.croissant.external.opensearch.query.SearchSubField.*;

import java.util.List;
import java.util.Map;

/**
 * 검색 매칭 상황(지역/빵/가게명·메뉴/자동완성)별 필드×부스트 프로파일.
 *
 * <p>랭킹 튜닝의 단위는 "필드"보다 "매칭 상황"이므로(예: 지역 매칭 전체 부스트 조정), 부스트를 여기 한 곳에 모은다.
 * {@code StoreSearchQueryBuilder}는 이 데이터를 읽어 nested/flat 쿼리를 조립만 한다.
 * 가게명 정확(term) 부스트와 function_score 파라미터는 {@link RankingWeights} 참조.
 */
public enum SearchMatchProfile {

	/** 지역 매칭 — searchRegions.name(nested) + searchRegionAliases(flat). */
	REGION(List.of(
		FieldBoost.nested(SEARCH_REGIONS, SEARCH_REGION_NAME, Map.of(BASE, 4f, NORI, 3f, EDGE, 2f, NGRAM, 2f)),
		FieldBoost.flat(SEARCH_REGION_ALIASES, Map.of(BASE, 4f, NORI, 3f, EDGE, 2f, NGRAM, 2f)))),

	/** 빵 매칭 — breadTypes(flat) + menus.name/menuCategory(nested). */
	BREAD(List.of(
		FieldBoost.flat(BREAD_TYPES, Map.of(BASE, 4f, NORI, 3f, EDGE, 2f, NGRAM, 2f)),
		FieldBoost.nested(MENUS, MENU_NAME, Map.of(BASE, 4f, EDGE, 3f, NGRAM, 2f)),
		FieldBoost.nested(MENUS, MENU_CATEGORY, Map.of(BASE, 2f, NORI, 1f, EDGE, 1f, NGRAM, 1f)))),

	/** 가게명·메뉴 부분 매칭 — 조사 제거 없는 edge/ngram만(원문/잔여 토큰 soft 보너스). */
	NAME_MENU_PARTIAL(List.of(
		FieldBoost.flat(STORE_NAME, Map.of(EDGE, 2f, NGRAM, 1f)),
		FieldBoost.nested(MENUS, MENU_NAME, Map.of(EDGE, 2f, NGRAM, 1f)))),

	/** 자동완성 — 가게명/빵/지역/메뉴 edge·ngram. */
	AUTOCOMPLETE(List.of(
		FieldBoost.flat(STORE_NAME, Map.of(EDGE, 5f, NGRAM, 2f)),
		FieldBoost.flat(BREAD_TYPES, Map.of(EDGE, 4f, NGRAM, 2f)),
		FieldBoost.flat(SEARCH_REGION_ALIASES, Map.of(EDGE, 2f, NGRAM, 1f)),
		FieldBoost.nested(MENUS, MENU_NAME, Map.of(EDGE, 4f, NGRAM, 2f)),
		FieldBoost.nested(MENUS, MENU_CATEGORY, Map.of(EDGE, 2f, NGRAM, 1f)),
		FieldBoost.nested(SEARCH_REGIONS, SEARCH_REGION_NAME, Map.of(EDGE, 2f, NGRAM, 1f))));

	private final List<FieldBoost> fields;

	SearchMatchProfile(List<FieldBoost> fields) {
		this.fields = fields;
	}

	public List<FieldBoost> fields() {
		return fields;
	}
}
