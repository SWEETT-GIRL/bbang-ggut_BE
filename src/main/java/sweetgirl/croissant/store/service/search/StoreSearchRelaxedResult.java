package sweetgirl.croissant.store.service.search;

/**
 * 토큰 완화 폴백으로 얻은 결과.
 *
 * @param stage     결과를 낸 완화 단계
 * @param keptToken 한 토큰만 남긴 단계에서 남긴 토큰(정규화된 값). 그 밖의 단계는 빈 문자열.
 */
public record StoreSearchRelaxedResult(
		StoreSearchResult result,
		SearchFallbackStage stage,
		String keptToken) {
}
