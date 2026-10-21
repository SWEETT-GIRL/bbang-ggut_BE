package sweetgirl.croissant.store.repository.search;

/**
 * PostgreSQL 자동완성 SQL 한 행.
 *
 * @param type    STORE | REGION (SQL 리터럴)
 * @param storeId 가게명·구명 후보만 채워진다
 * @param tier    0 = 접두 일치, 1 = 포함 일치 — 접두를 앞에 세우기 위한 정렬 키
 * @param steadyScore 가게의 평소 수준(store.trending_baseline) — 같은 tier 안에서 요즘 꾸준히 찾는 가게를 앞에 세운다. REGION 은 0
 * @param centerLat REGION 만 채워진다 — 고르면 지도가 갈 자리
 * @param centerLng REGION 만
 * @param radiusMeters REGION 만 — 지도를 얼마나 당길지
 * @param parentName REGION 만 — '중동'이 어느 중동인지 가르는 값
 */
public record StoreAutocompleteRow(
		String keyword,
		String type,
		Long storeId,
		int tier,
		double popularityScore,
		Double centerLat,
		Double centerLng,
		Integer radiusMeters,
		String parentName,
		double steadyScore) {

	/** 지역이 아닌 후보는 좌표가 없다 */
	public StoreAutocompleteRow(String keyword, String type, Long storeId, int tier, double popularityScore) {
		this(keyword, type, storeId, tier, popularityScore, null, null, null, null, 0);
	}

	public StoreAutocompleteRow(String keyword, String type, Long storeId, int tier, double popularityScore,
			Double centerLat, Double centerLng, Integer radiusMeters, String parentName) {
		this(keyword, type, storeId, tier, popularityScore, centerLat, centerLng, radiusMeters, parentName, 0);
	}
}
