package sweetgirl.croissant.course.event;

/**
 * 메모 초안이 Redis 에 새로 받아들여졌다(더 큰 seq). 일행 화면에 바로 보이도록 STOMP DRAFT 신호로 나간다 — 내용을 싣는다.
 * 트랜잭션이 없는 경로라 일반 이벤트다. 빈 content 는 "지우기 예약".
 */
public record MemoDraftWrittenEvent(Long courseId, Long storeId, Long memberId, String content, long seq) {
}
