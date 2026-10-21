package sweetgirl.croissant.dessert.enums;

/**
 * 디저트의 성격. 파는 곳을 어느 표에서 찾을지가 이 값으로 갈린다.
 *
 * <p>갈림길은 "편의점이냐"가 아니라 <b>브랜드 지점을 보느냐, 가게마다 근거를 보느냐</b>다.
 * CONVENIENCE·CHAIN 은 표만 다를 뿐 하는 일이 같다 — 브랜드 이름으로 가까운 지점을 찾는다.
 */
public enum DessertItemType {

	/** 편의점 상품. 브랜드 전체에서 판다 → convenience_store 의 해당 브랜드 지점 */
	CONVENIENCE,

	/** 체인 한정 메뉴(파리바게뜨 초코츄러스 …) → chain_store 의 해당 브랜드 지점 */
	CHAIN,

	/** 피자설기처럼 동네 가게가 각자 파는 것. 가게마다 파는지가 달라 dessert_item_store 로 연결한다 */
	GENERIC
}
