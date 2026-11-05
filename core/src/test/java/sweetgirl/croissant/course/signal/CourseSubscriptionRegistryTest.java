package sweetgirl.croissant.course.signal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import sweetgirl.croissant.course.signal.CourseSubscriptionRegistry.Subscriber;

@DisplayName("CourseSubscriptionRegistry")
class CourseSubscriptionRegistryTest {

	private static final Long COURSE_ID = 10L;

	private CourseSubscriptionRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new CourseSubscriptionRegistry();
		registry.subscribe(COURSE_ID, 1L, "s-1", "sub-a");
		registry.subscribe(COURSE_ID, 2L, "s-2", "sub-b");
		registry.subscribe(20L, 1L, "s-1", "sub-c");
	}

	@Test
	@DisplayName("코스별 구독 세션을 돌려준다")
	void subscribers_byCourse() {
		assertThat(registry.subscribers(COURSE_ID))
			.containsExactlyInAnyOrder(new Subscriber("s-1", 1L), new Subscriber("s-2", 2L));
		assertThat(registry.subscribers(20L)).containsExactly(new Subscriber("s-1", 1L));
		assertThat(registry.subscribers(999L)).isEmpty();
		assertThat(registry.sessionCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("UNSUBSCRIBE 는 그 구독만 뺀다 — 같은 세션의 다른 코스 구독은 남는다")
	void unsubscribe_removesOnlyThatSubscription() {
		registry.unsubscribe("s-1", "sub-a");

		assertThat(registry.subscribers(COURSE_ID)).containsExactly(new Subscriber("s-2", 2L));
		assertThat(registry.subscribers(20L)).containsExactly(new Subscriber("s-1", 1L));
	}

	@Test
	@DisplayName("DISCONNECT 는 세션의 모든 구독을 뺀다")
	void disconnect_removesAllSubscriptionsOfSession() {
		registry.disconnect("s-1");

		assertThat(registry.subscribers(COURSE_ID)).containsExactly(new Subscriber("s-2", 2L));
		assertThat(registry.subscribers(20L)).isEmpty();
		assertThat(registry.sessionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("프레임 없이 끊긴 세션도 SessionDisconnectEvent 로 정리된다")
	void sessionDisconnectEvent_cleansUp() {
		registry.onSessionDisconnect(new SessionDisconnectEvent(this,
			MessageBuilder.withPayload(new byte[0]).build(), "s-2", CloseStatus.GOING_AWAY));

		assertThat(registry.subscribers(COURSE_ID)).containsExactly(new Subscriber("s-1", 1L));
	}

	@Test
	@DisplayName("revoke(회원): 그 회원의 그 코스 구독만 뺀다 — 다른 코스 구독은 남는다")
	void revoke_member_removesOnlyThatMember() {
		registry.revoke(COURSE_ID, 1L);

		assertThat(registry.subscribers(COURSE_ID)).containsExactly(new Subscriber("s-2", 2L));
		assertThat(registry.subscribers(20L)).containsExactly(new Subscriber("s-1", 1L));
	}

	@Test
	@DisplayName("revoke(전원): 코스 삭제 — 그 코스의 구독을 모두 뺀다")
	void revoke_all_removesEveryone() {
		registry.revoke(COURSE_ID, null);

		assertThat(registry.subscribers(COURSE_ID)).isEmpty();
		assertThat(registry.subscribers(20L)).containsExactly(new Subscriber("s-1", 1L));
	}

	@Test
	@DisplayName("revoke 뒤 같은 세션이 DISCONNECT 해도 안전하다")
	void revokeThenDisconnect_isSafe() {
		registry.revoke(COURSE_ID, 1L);
		registry.disconnect("s-1");

		assertThat(registry.subscribers(COURSE_ID)).containsExactly(new Subscriber("s-2", 2L));
		assertThat(registry.subscribers(20L)).isEmpty();
	}
}
