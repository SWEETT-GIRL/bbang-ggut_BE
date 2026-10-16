package sweetgirl.croissant.searchmiss.enums;

/**
 * 실패 검색어 자동 판정 결과.
 *
 * <p>관리자 처리 상태({@link SearchMissStatus})와 따로 둔다. 판정은 "AI·코드가 어떻게 봤나"이고 상태는 "처리가 끝났나"다.
 * 둘을 나눠 남겨야 사람 판정과 견줘 오판율을 재고 자동 처리 범위를 조정할 수 있다.
 */
public enum SearchMissVerdict {

	/** 자모만·숫자만·한 글자·같은 글자 반복. 대응할 것이 없다. */
	JUNK,

	/** 다시 검색하니 결과가 나온다 — 그 사이 가게나 별칭이 추가됐다. */
	NOW_HIT,

	/** 우리 DB 에 있는 가게의 줄임말·오타였다. 별칭을 넣어 다음 검색부터 나오게 했다(또는 shadow mode 에서 넣을 예정). */
	ALIAS_RESOLVED,

	/** 네이버에는 있는 빵집·카페인데 우리 DB 에 없다. 가게 등록은 사람이 한다. */
	NEW_STORE_CANDIDATE,

	/** 가게가 아니라 메뉴·지역을 찾은 검색어. 가게 추가가 아니라 검색 개선의 근거다. */
	NOT_A_STORE,

	/** 우리 DB 에 있지만 삭제(폐업)된 가게. 다시 등록할지 사람이 본다. */
	EXISTS_BUT_DELETED,

	/** 근거가 모자라거나 검증에 실패했다. 발생 횟수 상위만 사람이 본다. */
	UNRESOLVED
}
