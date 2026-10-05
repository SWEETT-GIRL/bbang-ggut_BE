package sweetgirl.croissant.store.event;

import sweetgirl.croissant.store.enums.TrendingSignal;

/**
 * 로그인 회원이 가게에 관심을 보였다 — 급부상 순위표의 원재료. 찜·코스담기는 이미 있는 이벤트(StoreSavedEvent, CourseStoreAddedEvent)를
 * 그대로 받으므로 여기서는 이벤트가 없던 조회·가고싶은 가게·빵로그만 이것으로 낸다.
 */
public record StoreTrendingSignalEvent(Long memberId, Long storeId, TrendingSignal signal) {
}
