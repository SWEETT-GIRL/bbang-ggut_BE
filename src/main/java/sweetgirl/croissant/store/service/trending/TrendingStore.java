package sweetgirl.croissant.store.service.trending;

import java.util.List;

/**
 * 순위표에 오른 급부상 가게 한 곳.
 *
 * @param name           화면에 보일 이름
 * @param normalizedKeys 정규화한 가게명·별칭 — 검색어가 이 중 하나로 시작하면 자동완성에서 승격한다
 * @param score          급부상 점수(클수록 위)
 */
public record TrendingStore(long storeId, String name, List<String> normalizedKeys, double score) {

	public boolean startsWith(String normalizedQuery) {
		return normalizedKeys.stream().anyMatch(key -> key.startsWith(normalizedQuery));
	}
}
