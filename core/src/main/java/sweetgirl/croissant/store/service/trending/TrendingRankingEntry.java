package sweetgirl.croissant.store.service.trending;

import java.util.List;

/**
 * 급부상 순위표(trend:ranking) 한 줄.
 *
 * @param storeId 가게
 * @param score   급부상 점수
 * @param name    화면에 보여 줄 가게 이름
 * @param keys    검색어 매칭에 쓰는 정규화한 가게명·별칭
 */
record TrendingRankingEntry(long storeId, double score, String name, List<String> keys) {
}
