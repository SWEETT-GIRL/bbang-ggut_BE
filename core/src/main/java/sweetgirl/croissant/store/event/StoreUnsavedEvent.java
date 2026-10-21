package sweetgirl.croissant.store.event;

import java.time.LocalDateTime;

/** 가게 저장 해제 — saved_stores 는 하드 삭제라 이 이벤트가 유일한 흔적이다. 개인화가 UNSAVE 신호(−1)로 받는다(#663). */
public record StoreUnsavedEvent(Long memberId, Long storeId, LocalDateTime unsavedAt) {
}
