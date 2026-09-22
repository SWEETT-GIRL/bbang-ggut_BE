package sweetgirl.croissant.dessert.enums;

/**
 * 디저트의 성격. 지도가 어디서 파는 곳을 찾을지가 이 값으로 갈린다.
 */
public enum DessertItemType {

	/** 편의점 상품. 브랜드 전체에서 팔아 가게 단위 연결이 없다 — convenience_store 의 해당 브랜드 지점을 본다 */
	CONVENIENCE,

	/** 체인 한정 메뉴. 파이프라인이 브랜드 지점을 store 에 매칭해 dessert_item_store 에 넣는다 */
	CHAIN,

	/** 피자설기처럼 동네 가게가 각자 파는 것. 가게마다 파는지가 달라 dessert_item_store 로 연결한다 */
	GENERIC;

	/** 편의점만 빵긋 가게가 아니다 — 조회하는 표가 다르다 */
	public boolean isConvenience() {
		return this == CONVENIENCE;
	}
}
