package sweetgirl.croissant.store.repository.search;

/**
 * PostgreSQL 자동완성 SQL 한 행.
 *
 * @param type    STORE | REGION (SQL 리터럴)
 * @param storeId 가게명·구명 후보만 채워진다
 * @param tier    0 = 접두 일치, 1 = 포함 일치 — 접두를 앞에 세우기 위한 정렬 키
 */
public record StoreAutocompleteRow(
		String keyword,
		String type,
		Long storeId,
		int tier,
		double popularityScore) {
}
