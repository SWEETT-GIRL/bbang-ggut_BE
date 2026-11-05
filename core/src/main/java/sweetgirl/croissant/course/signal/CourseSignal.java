package sweetgirl.croissant.course.signal;

/**
 * 코스 구독자에게 가는 신호 하나. 서버 사이는 초안 Redis 의 pub/sub 채널로, 서버→앱은 STOMP {@code /user/queue/courses/{courseId}} 로 간다.
 *
 * <ul>
 * <li>{@code DRAFT} — 초안이 새로 받아들여졌다. 내용을 싣는다(신호마다 GET 이면 8명 × 0.5초 타자에서 코스당 초당 128회 읽기).
 * 빈 content 는 지우기 예약. 이름·얼굴은 싣지 않는다 — 앱이 상세 members 에서 memberId 로 찾는다.</li>
 * <li>{@code VERSION} — 코스 version 이 올라갔다. 내용 없음 → 앱이 상세를 다시 받는다(3초에 한 번으로 묶음).</li>
 * <li>{@code REVOKE} — 서버 사이에서만 쓴다. 나간 회원(memberId) 또는 전원(null)의 구독을 끊는다. 앱에는 가지 않는다.</li>
 * </ul>
 *
 * <p>서버 간 pub/sub 는 순서를 보장하지 않는다 — 앱은 seq 로 옛 DRAFT 를 버린다.
 */
public record CourseSignal(Type type, Long courseId, Long storeId, Long memberId, String content, Long seq) {

	public enum Type {
		DRAFT, VERSION, REVOKE
	}

	public static CourseSignal draft(Long courseId, Long storeId, Long memberId, String content, long seq) {
		return new CourseSignal(Type.DRAFT, courseId, storeId, memberId, content, seq);
	}

	public static CourseSignal version(Long courseId) {
		return new CourseSignal(Type.VERSION, courseId, null, null, null, null);
	}

	/** @param memberId 끊을 회원. null 이면 전원 */
	public static CourseSignal revoke(Long courseId, Long memberId) {
		return new CourseSignal(Type.REVOKE, courseId, null, memberId, null, null);
	}
}
