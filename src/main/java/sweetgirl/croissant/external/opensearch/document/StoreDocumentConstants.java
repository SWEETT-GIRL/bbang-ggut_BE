package sweetgirl.croissant.external.opensearch.document;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * OpenSearch store 문서의 필드명 상수.
 *
 * <p>StoreDocument record 필드 및 store-index.json 매핑과 반드시 일치해야 한다.
 * 색인 부분 업데이트(Map 키)와 검색 매칭 프로파일이 모두 이 상수를 참조해 필드명 오타를 원천 차단한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class StoreDocumentConstants {

	public static final String STORE_ID = "storeId";
	public static final String STORE_NAME = "storeName";
	public static final String STORE_NAME_ALIASES = "storeNameAliases";
	public static final String ADDRESS = "address";
	public static final String LOCATION = "location";
	public static final String SEARCH_REGIONS = "searchRegions";
	public static final String SEARCH_REGION_ALIASES = "searchRegionAliases";
	public static final String BREAD_TYPES = "breadTypes";
	public static final String MENUS = "menus";
	public static final String POPULARITY_SCORE = "popularityScore";

	// nested 하위 경로
	public static final String SEARCH_REGION_NAME = SEARCH_REGIONS + ".name";
	public static final String MENU_NAME = MENUS + ".name";
	public static final String MENU_CATEGORY = MENUS + ".menuCategory";
}
