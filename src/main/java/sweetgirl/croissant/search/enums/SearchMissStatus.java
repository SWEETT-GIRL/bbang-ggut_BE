package sweetgirl.croissant.search.enums;

/**
 * 결과를 못 낸 검색어의 처리 상태.
 *
 * <p>관리자가 목록을 소진하기 위한 값이다. 한 번 판단한 검색어가 다음 날 다시 뜨면 작업이 끝나지
 * 않으므로, 처리한 것은 {@link #PENDING}에서 빠져야 한다.
 */
public enum SearchMissStatus {

	/** 아직 보지 않음. 관리자 목록의 기본 필터. */
	PENDING,

	/** 실제로 없는 가게라 등록했다. */
	STORE_ADDED,

	/** 가게는 이미 있는데 검색이 못 찾은 경우. 검색 개선 작업의 근거 데이터가 된다. */
	ALREADY_EXISTS,

	/** 오타·장난 등 대응할 것이 없는 검색어. */
	IGNORED
}
