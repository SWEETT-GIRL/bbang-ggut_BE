package sweetgirl.croissant.store.enums;

/**
 * 가게명 별칭을 누가 넣었나. 자동으로 넣은 것만 골라 되돌릴 수 있게 구분한다.
 */
public enum StoreNameAliasSource {

	/** dataops 파이프라인이 넣은 구명(이름 변경 전 이름). DB 기본값이다. */
	DATAOPS,

	/** 실패 검색어 자동 판정이 넣은 줄임말·오타. */
	SEARCH_MISS_AUTO
}
