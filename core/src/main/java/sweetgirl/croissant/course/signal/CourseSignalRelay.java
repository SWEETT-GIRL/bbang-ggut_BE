package sweetgirl.croissant.course.signal;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.draft.CourseDraftConfig;

/**
 * Redis 채널에서 온 신호를 이 인스턴스의 구독 세션에 하나씩 보낸다. REVOKE 는 앱에 보내지 않고 명단에서 빼기만 한다.
 * 세션 지정 전송은 {@code /user/{sessionId}/queue/courses/{id}} 로 간다 — simple broker 가 그 세션의 구독으로만 풀어 준다.
 *
 * <p><b>본문은 채널에서 받은 JSON 바이트를 그대로 싣는다.</b> 구독자마다 객체를 다시 JSON 으로 바꾸면 같은 글을 구독자 수만큼
 * 직렬화하고, 그 과정의 잠금에서 리스너 스레드들이 서로를 기다린다 — 구독 소켓 12,000개 · 초당 신호 2만 건에서 리스너 스레드
 * 27개 중 16개가 BLOCKED 였고 전달 지연이 82초까지 쌓였다(2026-11-10 덤프). 채널의 JSON 과 앱이 받는 JSON 은 같은 모양이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class CourseSignalRelay implements MessageListener {

	private final ObjectMapper objectMapper;
	private final CourseSubscriptionRegistry registry;
	private final SimpMessagingTemplate messagingTemplate;

	/** {@code convertAndSendToUser} 가 붙이는 것과 같은 접두사 — WebSocketConfig 의 userDestinationPrefix */
	static final String USER_PREFIX = "/user/";

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String json = new String(message.getBody(), StandardCharsets.UTF_8);
		CourseSignal signal;
		try {
			signal = objectMapper.readValue(json, CourseSignal.class);
		} catch (java.io.IOException exception) {
			log.warn("[코스 신호] 읽을 수 없는 신호를 버린다 cause={} body={}", exception.getMessage(), json);
			return;
		}
		relay(signal, message.getBody());
	}

	/** @param body 앱에 보낼 본문 — 신호의 JSON. 구독자마다 다시 만들지 않는다 */
	void relay(CourseSignal signal, byte[] body) {
		if (signal.type() == CourseSignal.Type.REVOKE) {
			registry.revoke(signal.courseId(), signal.memberId());
			return;
		}
		String destination = "/queue/courses/" + signal.courseId();
		for (CourseSubscriptionRegistry.Subscriber subscriber : registry.subscribers(signal.courseId())) {
			messagingTemplate.send(USER_PREFIX + subscriber.sessionId() + destination,
				MessageBuilder.createMessage(body, sessionHeaders(subscriber.sessionId())));
		}
	}

	private static MessageHeaders sessionHeaders(String sessionId) {
		SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		accessor.setSessionId(sessionId);
		accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
		accessor.setLeaveMutable(true);
		return accessor.getMessageHeaders();
	}
}
