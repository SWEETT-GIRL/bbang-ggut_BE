package sweetgirl.croissant.alarm.event;

/**
 * 누가 남의 빵투어를 찜했다. 찜하기 트랜잭션이 커밋된 뒤에 알림이 쌓인다.
 *
 * <p>엔티티가 아니라 id만 실어 보낸다 — 리스너는 다른 트랜잭션·다른 스레드에서 돌아서
 * 여기 담긴 엔티티는 이미 준영속이다.
 */
public record CourseSavedEvent(Long courseId, Long actorId, Long ownerId) {
}
