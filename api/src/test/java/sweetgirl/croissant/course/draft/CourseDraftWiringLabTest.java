package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.course.signal.CourseSignalPublisher;
import sweetgirl.croissant.course.signal.CourseSignalRelay;
import sweetgirl.croissant.course.signal.CourseSubscriptionRegistry;
import sweetgirl.croissant.course.signal.StompAuthInterceptor;
import sweetgirl.croissant.course.signal.WebSocketConfig;
import sweetgirl.croissant.external.aws.S3Service;

/**
 * 초안 Redis 를 켠 채(course.draft.redis.mode=standalone) 전체 컨텍스트가 뜨는지 — 조건부 빈 전부·소켓 브로커·pub/sub 컨테이너·
 * 플러셔 라이프사이클이 서로 맞물리는지는 단위 테스트로 못 본다. 로컬 Redis(localhost:6379)가 있을 때 {@code -PlabTests} 로 돈다.
 * 기본 테스트(CroissantApplicationTests)는 mode 가 없어 이 빈들이 전부 빠진 쪽을 본다.
 */
@Tag("lab")
@SpringBootTest(properties = {
	"course.draft.redis.mode=standalone",
	"course.draft.redis.host=localhost",
	"course.draft.redis.port=6379",
	"course.draft.redis.database=15",
	"course.draft.write-enabled=true"})
@DisplayName("초안 Redis 를 켠 컨텍스트")
class CourseDraftWiringLabTest {

	@MockitoBean
	S3Service s3Service;

	@Autowired
	ApplicationContext context;

	@Test
	@DisplayName("초안·신호 빈이 전부 뜨고 플러셔가 돌고 있다")
	void draftBeansAreWiredAndFlusherRuns() {
		assertThat(context.getBean(MemoDraftRedis.class)).isNotNull();
		assertThat(context.getBean(MemoDraftBuffer.class)).isNotNull();
		assertThat(context.getBean(MemoDraftCommandService.class)).isNotNull();
		assertThat(context.getBean(MemoDraftQueryService.class)).isNotNull();
		assertThat(context.getBean(MemoDraftController.class)).isNotNull();
		assertThat(context.getBean(MemoDraftMetrics.class)).isNotNull();
		assertThat(context.getBean(MemoDraftFlusher.class).isRunning()).isTrue();
		assertThat(context.getBean(WebSocketConfig.class)).isNotNull();
		assertThat(context.getBean(StompAuthInterceptor.class)).isNotNull();
		assertThat(context.getBean(CourseSubscriptionRegistry.class)).isNotNull();
		assertThat(context.getBean(CourseSignalPublisher.class)).isNotNull();
		assertThat(context.getBean(CourseSignalRelay.class)).isNotNull();
		assertThat(context.getBean(RedisMessageListenerContainer.class).isRunning()).isTrue();
		assertThat(context.getBean(SimpMessagingTemplate.class)).isNotNull();
		// simple broker 하트비트 — 없으면 CONNECTED 가 heart-beat:0,0 이라 앱 하트비트가 꺼진다
		assertThat(context.getBean(org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler.class)
			.getHeartbeatValue()).containsExactly(25_000L, 25_000L);
		assertThat(context.getBean(CourseDraftProperties.class).isDraftEnabled()).isTrue();
	}

	@Test
	@DisplayName("캐시 Redis 자동 구성은 그대로 살아 있다 — 초안 Redis 홀더가 빈 타입을 가리지 않는다")
	void cacheRedisAutoConfigurationStillPresent() {
		assertThat(context.getBeanNamesForType(org.springframework.data.redis.core.StringRedisTemplate.class))
			.hasSize(1);
		assertThat(context.getBeanNamesForType(
			org.springframework.data.redis.connection.RedisConnectionFactory.class)).hasSize(1);
	}

	@Test
	@DisplayName("STOMP 수신 · 송신 채널은 전용 스레드 풀을 쓴다 — 추천 문장 LLM 풀을 나눠 쓰지 않는다")
	void stompChannelsDoNotShareTheLlmExecutor() {
		Object llmExecutor = context.getBean("reasonLlmExecutor");

		assertThat(context.getBean("clientInboundChannelExecutor")).isNotSameAs(llmExecutor);
		assertThat(context.getBean("clientOutboundChannelExecutor")).isNotSameAs(llmExecutor);
		assertThat(context.getBean("clientInboundChannelExecutor"))
			.isNotSameAs(context.getBean("clientOutboundChannelExecutor"));
	}
}
