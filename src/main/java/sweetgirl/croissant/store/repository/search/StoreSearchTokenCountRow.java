package sweetgirl.croissant.store.repository.search;

/**
 * 토큰별 후보 가게 수 SQL 한 행.
 *
 * @param tokenIndex     SQL 을 만들 때 넘긴 토큰 목록에서의 위치
 * @param candidateCount 그 토큰이 이름/구명에 든 가게 수. 상한(cap)에서 세기를 멈추므로 그 이상은 상한 값으로 온다.
 */
public record StoreSearchTokenCountRow(
		int tokenIndex,
		long candidateCount) {
}
