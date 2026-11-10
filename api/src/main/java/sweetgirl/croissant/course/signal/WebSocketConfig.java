package sweetgirl.croissant.course.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.draft.CourseDraftConfig;
import sweetgirl.croissant.course.draft.MemoDraftRedis;

/**
 * 코스 신호용 STOMP. 앱은 {@code /ws} 로 붙어(SockJS 없음, RN 은 순수 WebSocket) CONNECT 헤더로 인증하고
 * {@code /user/queue/courses/{courseId}} 를 구독한다. 브로커는 인스턴스 안의 simple broker — 서버 사이는 Redis pub/sub 가 잇는다.
 * 초안 Redis 가 설정된 환경에서만 뜬다(초안 자동저장과 한 묶음).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	static final long HEARTBEAT_MS = 25_000L;
	/** 인스턴스 하나가 받을 소켓 수보다 넉넉히 — 32GB 한 대에 10만 접속까지 확인했다 */
	static final int BROKER_CACHE_LIMIT = 200_000;

	private final StompAuthInterceptor stompAuthInterceptor;

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// 앱(네이티브)에는 Origin 이 없고, nginx 가 /ws 를 업그레이드해 넘긴다
		registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
	}

	/**
	 * STOMP 하트비트용 스케줄러. simple broker 는 스케줄러가 없으면 CONNECTED 에 {@code heart-beat:0,0} 으로 답해 앱의 하트비트가 꺼지고,
	 * nginx 유휴 300초에 끊겨 5분마다 재연결하게 된다. {@code TaskScheduler} 빈으로 두지 않는다 — Boot 가 모든 {@code @Scheduled} 의
	 * 스케줄러로 삼는다(플러셔와 같은 이유).
	 */
	private final ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();

	@PostConstruct
	void startHeartbeatScheduler() {
		heartbeatScheduler.setPoolSize(1);
		heartbeatScheduler.setThreadNamePrefix("stomp-heartbeat-");
		heartbeatScheduler.initialize();
	}

	@PreDestroy
	void stopHeartbeatScheduler() {
		heartbeatScheduler.shutdown();
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		// 서버 → 앱 25초마다, 앱 → 서버 25초마다 기대. 앱(stompjs)은 협상 결과로 자기 하트비트를 켠다
		registry.enableSimpleBroker("/queue")
			.setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
			.setTaskScheduler(heartbeatScheduler);
		registry.setUserDestinationPrefix("/user");
		// simple broker 는 "목적지 → 구독 세션" 을 캐시하는데 기본 1,024개다. 신호는 세션마다 다른 목적지
		// (/queue/courses/{id}-user{session})로 가므로 소켓이 그보다 많으면 캐시가 계속 밀려나고, 밀려난 목적지는 보낼 때마다
		// 전체 세션의 구독을 처음부터 훑는다 — 소켓 12,000개에서 초당 신호 2만 건이 3~4천 건으로 막혔다(2026-11-10).
		registry.setCacheLimit(BROKER_CACHE_LIMIT);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(stompAuthInterceptor);
	}

	/** Redis 채널 구독 — 모든 인스턴스가 같은 채널을 들고 자기 세션에만 흘린다. */
	@Bean
	public RedisMessageListenerContainer courseSignalListenerContainer(MemoDraftRedis redis, CourseSignalRelay relay) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redis.connectionFactory());
		container.addMessageListener(relay, new ChannelTopic(CourseSignalPublisher.CHANNEL));
		// 기동 시점에 Redis 에 못 붙어도 앱이 떠야 한다 — CourseSignalSubscriptionStarter 가 재시도하며 시작한다
		container.setAutoStartup(false);
		return container;
	}
}
