package sweetgirl.croissant.course.event;

/**
 * 코스의 version 이 올라갔다 — 가게·메모·멤버·제목 등 상세에 보이는 무엇이 바뀌었다. 커밋 뒤 STOMP VERSION 신호로 나간다(내용 없음).
 * {@code CourseVersionBumper.bump} 만 낸다 — 발행 지점이 하나라 신호 누락이 한 곳에서 막힌다.
 */
public record CourseChangedEvent(Long courseId) {
}
