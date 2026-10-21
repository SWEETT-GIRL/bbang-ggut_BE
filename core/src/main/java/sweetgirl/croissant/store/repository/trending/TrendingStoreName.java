package sweetgirl.croissant.store.repository.trending;

import java.util.List;

/**
 * 순위표에 함께 싣는 가게 이름. 자동완성은 검색어가 이 키들 중 하나로 시작하면 승격한다.
 *
 * @param name           화면에 보일 이름
 * @param normalizedKeys 정규화한 가게명과 별칭 — 검색어(접두어)와 비교할 값
 */
public record TrendingStoreName(long storeId, String name, List<String> normalizedKeys) {
}
