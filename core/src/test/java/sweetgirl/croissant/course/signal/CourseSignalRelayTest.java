package sweetgirl.croissant.course.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.course.signal.CourseSubscriptionRegistry.Subscriber;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourseSignalRelay")
class CourseSignalRelayTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private CourseSubscriptionRegistry registry;
	@Mock
	private SimpMessagingTemplate messagingTemplate;

	private CourseSignalRelay relay;

	@BeforeEach
	void setUp() {
		relay = new CourseSignalRelay(objectMapper, registry, messagingTemplate);
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<Message<byte[]>> messageCaptor() {
		return ArgumentCaptor.forClass(Message.class);
	}

	private DefaultMessage redisMessage(CourseSignal signal) throws Exception {
		return new DefaultMessage(CourseSignalPublisher.CHANNEL.getBytes(StandardCharsets.UTF_8),
			objectMapper.writeValueAsBytes(signal));
	}

	@Test
	@DisplayName("DRAFT 는 구독 세션마다 /user/{session}/queue/courses/{id} 로, 받은 JSON 바이트 그대로 보낸다")
	void draft_sentToEachSubscriberWithRawBody() throws Exception {
		CourseSignal signal = CourseSignal.draft(10L, 100L, 7L, "소금빵", 1_000L);
		DefaultMessage redisMessage = redisMessage(signal);
		given(registry.subscribers(10L)).willReturn(Set.of(new Subscriber("s-1", 1L), new Subscriber("s-2", 2L)));

		relay.onMessage(redisMessage, null);

		ArgumentCaptor<Message<byte[]>> sent = messageCaptor();
		then(messagingTemplate).should().send(eq("/user/s-1/queue/courses/10"), sent.capture());
		then(messagingTemplate).should().send(eq("/user/s-2/queue/courses/10"),
			any(Message.class));
		// 구독자마다 다시 직렬화하지 않는다 — 채널에서 받은 바이트 그대로
		assertThat(sent.getValue().getPayload()).isEqualTo(redisMessage.getBody());
		MessageHeaders headers = sent.getValue().getHeaders();
		assertThat(SimpMessageHeaderAccessor.getSessionId(headers)).isEqualTo("s-1");
		assertThat(headers.get(MessageHeaders.CONTENT_TYPE)).isEqualTo(MimeTypeUtils.APPLICATION_JSON);
		assertThat(objectMapper.readValue(sent.getValue().getPayload(), CourseSignal.class)).isEqualTo(signal);
	}

	@Test
	@DisplayName("구독자가 없으면 아무것도 보내지 않는다")
	void noSubscribers_noSend() throws Exception {
		given(registry.subscribers(10L)).willReturn(Set.of());

		relay.onMessage(redisMessage(CourseSignal.version(10L)), null);

		then(messagingTemplate).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("REVOKE 는 앱에 보내지 않고 명단에서만 뺀다")
	void revoke_updatesRegistryOnly() throws Exception {
		relay.onMessage(redisMessage(CourseSignal.revoke(10L, 7L)), null);

		then(registry).should().revoke(10L, 7L);
		then(registry).should(never()).subscribers(any());
		then(messagingTemplate).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("읽을 수 없는 신호는 버린다")
	void malformed_dropped() {
		DefaultMessage broken = new DefaultMessage(CourseSignalPublisher.CHANNEL.getBytes(StandardCharsets.UTF_8),
			"not json".getBytes(StandardCharsets.UTF_8));

		assertThatCode(() -> relay.onMessage(broken, null)).doesNotThrowAnyException();
		then(messagingTemplate).shouldHaveNoInteractions();
	}
}
