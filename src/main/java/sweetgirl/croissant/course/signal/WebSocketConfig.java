package sweetgirl.croissant.course.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

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

	private final StompAuthInterceptor stompAuthInterceptor;

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// 앱(네이티브)에는 Origin 이 없고, nginx 가 /ws 를 업그레이드해 넘긴다
		registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/queue");
		registry.setUserDestinationPrefix("/user");
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
		return container;
	}
}
