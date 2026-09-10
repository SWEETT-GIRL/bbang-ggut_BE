package sweetgirl.croissant.alarm.event;

/**
 * "이 알림을 푸시로도 내보내라." 알림을 적재한 트랜잭션이 커밋된 뒤에 처리된다.
 *
 * <p>엔티티가 아니라 id만 싣는다. 이 이벤트를 받는 쪽은 다른 트랜잭션·다른 스레드라, 넘겨받은
 * 엔티티는 이미 준영속이라 지연 로딩이 터진다. id로 다시 읽으면 그 시점의 값을 보게 되는 것도
 * 맞다 — 커밋되지 않은 상태를 푸시로 내보낼 이유가 없다.
 *
 * @param notificationId 내보낼 알림
 * @param memberId 받을 사람. null이면 그 알림의 수신자 전원(공지)
 */
public record PushRequestedEvent(Long notificationId, Long memberId) {

	public static PushRequestedEvent to(Long notificationId, Long memberId) {
		return new PushRequestedEvent(notificationId, memberId);
	}

	public static PushRequestedEvent toEveryone(Long notificationId) {
		return new PushRequestedEvent(notificationId, null);
	}

	public boolean isBroadcast() {
		return this.memberId == null;
	}
}
