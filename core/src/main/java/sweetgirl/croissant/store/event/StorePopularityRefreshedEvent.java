package sweetgirl.croissant.store.event;

/**
 * store.popularity_score 를 다시 계산했다 — 자동완성·검색 결과의 순서가 통째로 바뀔 수 있다.
 *
 * @param updatedStoreCount 값이 실제로 바뀐 가게 수
 */
public record StorePopularityRefreshedEvent(int updatedStoreCount) {
}
