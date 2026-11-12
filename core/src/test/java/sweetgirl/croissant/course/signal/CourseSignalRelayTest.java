package sweetgirl.croissant.course.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.course.signal.CourseSubscriptionRegistry.Subscriber;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourseSignalRelay")
class CourseSignalRelayTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private CourseSubscriptionRegistry registry;
	@Mock
	private SimpMessagingTemplate messagingTemplate;

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private CourseSignalRelay relay;

	@BeforeEach
	void setUp() {
		relay = new CourseSignalRelay(objectMapper, registry, messagingTemplate, meterRegistry, () -> 0);
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<Message<byte[]>> messageCaptor() {
		return ArgumentCaptor.forClass(Message.class);
	}

	/** 전송 스레드를 띄우지 않고, 칸에 든 것을 이 스레드에서 전부 보낸다 */
	private void dispatchAll() throws InterruptedException {
		while (relay.dispatchNext(0)) {
			// 칸이 빌 때까지
		}
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
		dispatchAll();

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
		dispatchAll();

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

	@Test
	@DisplayName("밀려 있는 동안 같은 칸(코스 · 정류장 · 회원)에 온 DRAFT 는 최신 하나만 보낸다 — 다른 칸은 그대로 간다")
	void draft_sameSlotConflatedToLatest() throws Exception {
		given(registry.subscribers(10L)).willReturn(Set.of(new Subscriber("s-1", 1L)));
		CourseSignal latest = CourseSignal.draft(10L, 100L, 7L, "소금빵 꼭", 3_000L);

		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "소", 1_000L)), null);
		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "소금", 2_000L)), null);
		relay.onMessage(redisMessage(latest), null);
		relay.onMessage(redisMessage(CourseSignal.draft(10L, 200L, 7L, "다른 정류장", 1_500L)), null);
		dispatchAll();

		ArgumentCaptor<Message<byte[]>> sent = messageCaptor();
		then(messagingTemplate).should(times(2)).send(eq("/user/s-1/queue/courses/10"), sent.capture());
		assertThat(sent.getAllValues())
			.extracting(message -> objectMapper.readValue(message.getPayload(), CourseSignal.class).content())
			.containsExactly("소금빵 꼭", "다른 정류장");
		assertThat(meterRegistry.counter("course_signal_conflated_total").count()).isEqualTo(2.0);
	}

	@Test
	@DisplayName("늦게 도착한 옛 seq 는 기다리던 더 새 글을 덮어쓰지 못한다")
	void draft_olderSeqDoesNotReplaceNewer() throws Exception {
		given(registry.subscribers(10L)).willReturn(Set.of(new Subscriber("s-1", 1L)));

		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "새 글", 2_000L)), null);
		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "옛 글", 1_000L)), null);
		dispatchAll();

		ArgumentCaptor<Message<byte[]>> sent = messageCaptor();
		then(messagingTemplate).should().send(eq("/user/s-1/queue/courses/10"), sent.capture());
		assertThat(objectMapper.readValue(sent.getValue().getPayload(), CourseSignal.class).content())
			.isEqualTo("새 글");
	}

	@Test
	@DisplayName("VERSION 은 코스 하나에 하나로 합치고, 같은 코스의 DRAFT 와는 섞지 않는다")
	void version_conflatedPerCourse() throws Exception {
		given(registry.subscribers(10L)).willReturn(Set.of(new Subscriber("s-1", 1L)));

		relay.onMessage(redisMessage(CourseSignal.version(10L)), null);
		relay.onMessage(redisMessage(CourseSignal.version(10L)), null);
		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "소금빵", 1_000L)), null);
		dispatchAll();

		then(messagingTemplate).should(times(2)).send(eq("/user/s-1/queue/courses/10"), any(Message.class));
	}

	@Test
	@DisplayName("보낸 뒤 같은 칸에 다시 온 신호는 새로 보낸다 — 한 번 보낸 칸이 막히지 않는다")
	void slotReusableAfterDispatch() throws Exception {
		given(registry.subscribers(10L)).willReturn(Set.of(new Subscriber("s-1", 1L)));

		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "소", 1_000L)), null);
		dispatchAll();
		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "소금", 2_000L)), null);
		dispatchAll();

		then(messagingTemplate).should(times(2)).send(eq("/user/s-1/queue/courses/10"), any(Message.class));
		assertThat(meterRegistry.counter("course_signal_conflated_total").count()).isZero();
	}

	private static Set<Subscriber> subscribers(int count) {
		return IntStream.range(0, count).mapToObj(index -> new Subscriber("s-" + index, (long)index))
			.collect(Collectors.toSet());
	}

	private List<String> sentSessions() {
		ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
		then(messagingTemplate).should(atLeast(0)).send(destination.capture(), any(Message.class));
		return destination.getAllValues().stream().map(value -> value.split("/")[2]).toList();
	}

	@Test
	@DisplayName("구독자가 많은 코스는 끊어서 보낸다 — 한 번에 200명, 남은 몫은 줄 뒤로 가서 다른 코스가 먼저 나간다")
	void bigCourse_sentInChunksLettingOthersInterleave() throws Exception {
		given(registry.subscribers(10L)).willReturn(subscribers(450));
		given(registry.subscribers(20L)).willReturn(Set.of(new Subscriber("small", 1L)));

		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "인기 코스", 1_000L)), null);
		relay.onMessage(redisMessage(CourseSignal.draft(20L, 100L, 7L, "작은 코스", 1_000L)), null);

		relay.dispatchNext(0);
		assertThat(sentSessions()).hasSize(CourseSignalRelay.CHUNK).doesNotContain("small");

		relay.dispatchNext(0);
		assertThat(sentSessions()).hasSize(CourseSignalRelay.CHUNK + 1).contains("small");

		dispatchAll();
		List<String> all = sentSessions();
		assertThat(all).hasSize(451);
		assertThat(Set.copyOf(all)).hasSize(451); // 한 사람에게 두 번 가지 않았다
	}

	@Test
	@DisplayName("끊어 보내는 중에 더 새 신호가 오면 남은 몫은 버리고, 새 신호는 멈춘 자리부터 보낸다 — 뒷사람이 굶지 않는다")
	void bigCourse_newerSignalResumesFromCursor() throws Exception {
		given(registry.subscribers(10L)).willReturn(subscribers(450));

		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "소", 1_000L)), null);
		relay.dispatchNext(0);
		List<String> firstChunk = sentSessions();

		relay.onMessage(redisMessage(CourseSignal.draft(10L, 100L, 7L, "소금", 2_000L)), null);
		relay.dispatchNext(0); // 옛 신호의 남은 몫 — 버린다
		assertThat(sentSessions()).hasSize(CourseSignalRelay.CHUNK);
		relay.dispatchNext(0); // 새 신호의 첫 몫 — 아직 못 받은 사람부터
		List<String> secondChunk = sentSessions().subList(CourseSignalRelay.CHUNK, 2 * CourseSignalRelay.CHUNK);

		assertThat(secondChunk).doesNotContainAnyElementsOf(firstChunk);
		dispatchAll();
		// 새 신호는 결국 전원에게 갔다(멈춘 자리부터 한 바퀴)
		assertThat(Set.copyOf(sentSessions().subList(CourseSignalRelay.CHUNK, sentSessions().size()))).hasSize(450);
	}
}
