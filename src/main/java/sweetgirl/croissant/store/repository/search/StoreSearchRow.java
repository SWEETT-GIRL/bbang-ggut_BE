package sweetgirl.croissant.store.repository.search;

/**
 * PostgreSQL 검색 SQL 한 행. 점수 성분을 따로 들고 와서 엔진이 matchedFields·커버리지 판정에 쓴다.
 *
 * @param distanceMeters 좌표가 없으면 null
 */
public record StoreSearchRow(
		long storeId,
		double score,
		double popularityScore,
		double nameScore,
		double aliasScore,
		double regionScore,
		double addressScore,
		Double distanceMeters) {

	public boolean matchedByName() {
		return nameScore > 0 || aliasScore > 0;
	}

	public boolean matchedByRegion() {
		return regionScore > 0;
	}
}
