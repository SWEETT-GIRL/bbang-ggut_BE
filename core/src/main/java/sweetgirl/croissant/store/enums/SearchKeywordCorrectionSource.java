package sweetgirl.croissant.store.enums;

/**
 * 검색어 교정을 누가 넣었나. 자동으로 넣은 것만 골라 되돌릴 수 있게 구분한다.
 */
public enum SearchKeywordCorrectionSource {

	/** 실패 검색어 자동 판정이 찾은 오타·줄임말·별명 */
	SEARCH_MISS_AUTO,

	/** 관리자가 직접 넣은 교정 */
	ADMIN
}
