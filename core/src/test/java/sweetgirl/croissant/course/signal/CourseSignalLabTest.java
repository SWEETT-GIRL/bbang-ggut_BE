package sweetgirl.croissant.course.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.course.draft.CourseDraftProperties;
import sweetgirl.croissant.course.draft.MemoDraftRedis;
import sweetgirl.croissant.course.event.CourseMemberLeftEvent;
import sweetgirl.croissant.course.event.MemoDraftWrittenEvent;

/**
 * 발행 → Redis 채널 → 릴레이 → 세션 전송을 진짜 Redis 로 끝까지 돈다. 서버가 여러 대일 때 다른 인스턴스의 쓰기가
 * 내 세션에 닿는 경로가 바로 이것이다(인스턴스 하나 안에서도 같은 길을 지난다).
 * 로컬 Redis(localhost:6379)가 떠 있을 때 {@code ./gradlew test -PlabTests} 로 돈다. 15번 DB.
 */
@Tag("lab")
@DisplayName("코스 신호 (실제 Redis pub/sub)")
class CourseSignalLabTest {

	private static final long COURSE_ID = 77L;

	private MemoDraftRedis redis;
	private RedisMessageListenerContainer container;
	private CourseSubscriptionRegistry registry;
	private SimpMessagingTemplate messagingTemplate;
	private CourseSignalPublisher publisher;

	@BeforeEach
	void setUp() {
		CourseDraftProperties properties = new CourseDraftProperties();
		properties.getRedis().setMode(CourseDraftProperties.Mode.STANDALONE);
		properties.getRedis().setHost("localhost");
		properties.getRedis().setPort(6379);
		properties.getRedis().setDatabase(15);
		redis = MemoDraftRedis.connect(properties.getRedis());

		ObjectMapper objectMapper = new ObjectMapper();
		registry = new CourseSubscriptionRegistry();
		messagingTemplate = mock(SimpMessagingTemplate.class);
		CourseSignalRelay relay = new CourseSignalRelay(objectMapper, registry, messagingTemplate);
		container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redis.connectionFactory());
		container.addMessageListener(relay, new ChannelTopic(CourseSignalPublisher.CHANNEL));
		container.afterPropertiesSet();
		container.start();
		publisher = new CourseSignalPublisher(redis, objectMapper);
	}

	@AfterEach
	void tearDown() {
		container.stop();
		redis.close();
	}

	@Test
	@DisplayName("초안 쓰기 이벤트가 채널을 지나 구독 세션 둘에 각각 DRAFT 로 도착한다")
	void draft_reachesEverySubscribedSession() {
		registry.subscribe(COURSE_ID, 1L, "s-1", "sub-a");
		registry.subscribe(COURSE_ID, 2L, "s-2", "sub-b");
		registry.subscribe(99L, 3L, "s-3", "sub-c");

		publisher.onDraftWritten(new MemoDraftWrittenEvent(COURSE_ID, 100L, 1L, "소금빵 꼭", 1_000L));

		then(messagingTemplate).should(timeout(3_000)).send(eq("/user/s-1/queue/courses/77"), any(Message.class));
		then(messagingTemplate).should(timeout(3_000)).send(eq("/user/s-2/queue/courses/77"), any(Message.class));
		then(messagingTemplate).should(never()).send(eq("/user/s-3/queue/courses/77"), any(Message.class));
	}

	@Test
	@DisplayName("나가기 이벤트가 채널을 지나 그 회원의 구독을 빼고, 다음 신호는 그 세션에 가지 않는다")
	void memberLeft_revokesAcrossChannel() {
		registry.subscribe(COURSE_ID, 1L, "s-1", "sub-a");
		registry.subscribe(COURSE_ID, 2L, "s-2", "sub-b");

		publisher.onMemberLeft(new CourseMemberLeftEvent(COURSE_ID, 2L));
		waitUntil(() -> registry.subscribers(COURSE_ID).size() == 1);

		publisher.onDraftWritten(new MemoDraftWrittenEvent(COURSE_ID, 100L, 1L, "둘만 남은 글", 2_000L));

		then(messagingTemplate).should(timeout(3_000)).send(eq("/user/s-1/queue/courses/77"), any(Message.class));
		then(messagingTemplate).should(never()).send(eq("/user/s-2/queue/courses/77"), any(Message.class));
		assertThat(registry.subscribers(COURSE_ID)).extracting(CourseSubscriptionRegistry.Subscriber::memberId)
			.containsExactly(1L);
	}

	private static void waitUntil(java.util.function.BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + 3_000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("3초 안에 조건이 만족되지 않았다");
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}
	}
}
