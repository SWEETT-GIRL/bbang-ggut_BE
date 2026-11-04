package sweetgirl.croissant.course.signal;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.draft.CourseDraftConfig;

/**
 * Redis 채널에서 온 신호를 이 인스턴스의 구독 세션에 하나씩 보낸다. REVOKE 는 앱에 보내지 않고 명단에서 빼기만 한다.
 * 세션 지정 전송은 {@code /user/{sessionId}/queue/courses/{id}} 로 간다 — simple broker 가 그 세션의 구독으로만 풀어 준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class CourseSignalRelay implements MessageListener {

	private final ObjectMapper objectMapper;
	private final CourseSubscriptionRegistry registry;
	private final SimpMessagingTemplate messagingTemplate;

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
		relay(signal);
	}

	void relay(CourseSignal signal) {
		if (signal.type() == CourseSignal.Type.REVOKE) {
			registry.revoke(signal.courseId(), signal.memberId());
			return;
		}
		String destination = "/queue/courses/" + signal.courseId();
		for (CourseSubscriptionRegistry.Subscriber subscriber : registry.subscribers(signal.courseId())) {
			messagingTemplate.convertAndSendToUser(subscriber.sessionId(), destination, signal,
				sessionHeaders(subscriber.sessionId()));
		}
	}

	private static MessageHeaders sessionHeaders(String sessionId) {
		SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		accessor.setSessionId(sessionId);
		accessor.setLeaveMutable(true);
		return accessor.getMessageHeaders();
	}
}
