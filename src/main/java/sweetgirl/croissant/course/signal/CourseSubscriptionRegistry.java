package sweetgirl.croissant.course.signal;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import sweetgirl.croissant.course.draft.CourseDraftConfig;

/**
 * 이 인스턴스에 붙은 소켓 세션이 어느 코스를 구독하는지. 신호는 브로커의 토픽 방송이 아니라 여기 등록된 세션에 하나씩 보낸다 —
 * simple broker 는 세션별로 구독을 끊을 수 없어서, 나간 회원을 빼려면 보내는 쪽이 명단을 들고 있어야 한다.
 *
 * <p>세션은 한 인스턴스에만 붙으므로 인스턴스 메모리로 충분하다. 나가기·삭제는 REVOKE 신호로 모든 인스턴스에 돌아 각자 자기 명단에서 뺀다.
 */
@Component
@ConditionalOnProperty(name = CourseDraftConfig.REDIS_MODE_PROPERTY)
public class CourseSubscriptionRegistry {

	public record Subscriber(String sessionId, Long memberId) {
	}

	private record Subscription(Long courseId, Long memberId) {
	}

	private final Map<Long, Set<Subscriber>> subscribersByCourse = new ConcurrentHashMap<>();
	/** sessionId → (subscriptionId → 구독). UNSUBSCRIBE 는 subscriptionId 만 들고 오고, DISCONNECT 는 세션 전체를 닫는다. */
	private final Map<String, Map<String, Subscription>> subscriptionsBySession = new ConcurrentHashMap<>();

	public void subscribe(Long courseId, Long memberId, String sessionId, String subscriptionId) {
		subscriptionsBySession.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>())
			.put(subscriptionId, new Subscription(courseId, memberId));
		subscribersByCourse.computeIfAbsent(courseId, id -> ConcurrentHashMap.newKeySet())
			.add(new Subscriber(sessionId, memberId));
	}

	public void unsubscribe(String sessionId, String subscriptionId) {
		Map<String, Subscription> subscriptions = subscriptionsBySession.get(sessionId);
		if (subscriptions == null) {
			return;
		}
		Subscription removed = subscriptions.remove(subscriptionId);
		if (removed != null) {
			remove(removed.courseId(), new Subscriber(sessionId, removed.memberId()));
		}
		if (subscriptions.isEmpty()) {
			subscriptionsBySession.remove(sessionId);
		}
	}

	public void disconnect(String sessionId) {
		Map<String, Subscription> subscriptions = subscriptionsBySession.remove(sessionId);
		if (subscriptions == null) {
			return;
		}
		subscriptions.values().forEach(subscription -> remove(subscription.courseId(),
			new Subscriber(sessionId, subscription.memberId())));
	}

	/** DISCONNECT 프레임 없이 끊긴 세션(네트워크 단절·앱 종료)도 여기서 정리된다. */
	@EventListener
	public void onSessionDisconnect(SessionDisconnectEvent event) {
		disconnect(event.getSessionId());
	}

	/** 나간 회원의 구독을 이 인스턴스에서 뺀다. memberId 가 null 이면 코스 전원. */
	public void revoke(Long courseId, Long memberId) {
		Set<Subscriber> subscribers = subscribersByCourse.get(courseId);
		if (subscribers == null) {
			return;
		}
		subscribers.removeIf(subscriber -> memberId == null || memberId.equals(subscriber.memberId()));
		subscriptionsBySession.values().forEach(subscriptions -> subscriptions.values()
			.removeIf(subscription -> subscription.courseId().equals(courseId)
				&& (memberId == null || memberId.equals(subscription.memberId()))));
		if (subscribers.isEmpty()) {
			subscribersByCourse.remove(courseId);
		}
	}

	public Set<Subscriber> subscribers(Long courseId) {
		return Set.copyOf(subscribersByCourse.getOrDefault(courseId, Set.of()));
	}

	/** 게이지용 — 지금 이 인스턴스에 붙은 세션 수. */
	public int sessionCount() {
		return subscriptionsBySession.size();
	}

	private void remove(Long courseId, Subscriber subscriber) {
		Set<Subscriber> subscribers = subscribersByCourse.get(courseId);
		if (subscribers == null) {
			return;
		}
		subscribers.remove(subscriber);
		if (subscribers.isEmpty()) {
			subscribersByCourse.remove(courseId);
		}
	}
}
