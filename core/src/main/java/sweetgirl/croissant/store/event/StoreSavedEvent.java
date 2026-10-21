package sweetgirl.croissant.store.event;

import java.time.LocalDateTime;

/**
 * 가게 저장. 개인화가 SAVE 신호(+1)로 바로 받는다(#663) — 저장은 04:00 배치도 saved_stores 에서 읽지만, 배치 전에 해제되면
 * 행이 지워져(하드 삭제) UNSAVE(−1)만 남기 때문에 이벤트로 짝을 맞춘다. savedAt 은 엔티티의 created_at 과 같은 값이라 배치 행과
 * 유니크 키가 정확히 겹쳐 중복되지 않는다(PR #747 리뷰).
 */
public record StoreSavedEvent(Long memberId, Long storeId, LocalDateTime savedAt) {
}
