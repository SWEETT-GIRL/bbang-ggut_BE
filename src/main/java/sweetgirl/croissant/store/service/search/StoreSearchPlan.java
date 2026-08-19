package sweetgirl.croissant.store.service.search;

import java.util.List;

/**
 * 검색 엔진이 SQL 로 옮길 검색 조건. 모든 문자열은 이미 정규화(소문자·한글영숫자만)된 값이다.
 *
 * @param regionTokens  지역 사전에 잡힌 토큰들(OR). 비어 있으면 지역 조건 없음.
 * @param nameTokens    가게명/구명에 각각 부분일치해야 하는 토큰들(AND). 빵 단어·잔여 토큰이 모두 여기로 온다.
 * @param wholeQuery    불용어를 뺀 검색어 전체를 정규화한 것 — 랭킹(정확/접두/포함 계단)과 지역만 검색의 이름 OR 조건에 쓴다.
 * @param addressFallback true 면 지역·이름 조건 대신 주소 부분일치(wholeQuery)만으로 후보를 뽑는다(1차 0건 폴백).
 */
public record StoreSearchPlan(
		List<String> regionTokens,
		List<String> nameTokens,
		String wholeQuery,
		boolean addressFallback) {

	public boolean hasRegionTokens() {
		return !regionTokens.isEmpty();
	}

	public boolean hasNameTokens() {
		return !nameTokens.isEmpty();
	}

	public boolean hasWholeQuery() {
		return wholeQuery != null && !wholeQuery.isBlank();
	}

	/** 후보를 하나도 뽑을 수 없는 조건인지 — 지역도 이름도 검색어도 없으면 SQL 을 보내지 않는다. */
	public boolean isEmpty() {
		if (addressFallback) {
			return !hasWholeQuery();
		}
		return !hasRegionTokens() && !hasNameTokens() && !hasWholeQuery();
	}
}
