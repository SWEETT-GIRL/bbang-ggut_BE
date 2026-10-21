package sweetgirl.croissant.store.event;

/**
 * store.trending_baseline 을 다시 계산했다 — 자동완성의 스테디 정렬이 바뀔 수 있다.
 *
 * @param updatedStoreCount 값이 실제로 바뀐 가게 수
 */
public record StoreTrendingBaselineRefreshedEvent(int updatedStoreCount) {
}
