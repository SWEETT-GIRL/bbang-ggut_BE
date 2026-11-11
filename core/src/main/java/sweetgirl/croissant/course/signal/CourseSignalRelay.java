package sweetgirl.croissant.course.signal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.draft.CourseDraftConfig;

/**
 * Redis 채널에서 온 신호를 이 인스턴스의 구독 세션에 하나씩 보낸다. REVOKE 는 앱에 보내지 않고 명단에서 빼기만 한다.
 * 세션 지정 전송은 {@code /user/{sessionId}/queue/courses/{id}} 로 간다 — simple broker 가 그 세션의 구독으로만 풀어 준다.
 *
 * <p><b>본문은 채널에서 받은 JSON 바이트를 그대로 싣는다.</b> 구독자마다 객체를 다시 JSON 으로 바꾸면 같은 글을 구독자 수만큼
 * 직렬화하고, 그 과정의 잠금에서 리스너 스레드들이 서로를 기다린다 — 구독 소켓 12,000개 · 초당 신호 2만 건에서 리스너 스레드
 * 27개 중 16개가 BLOCKED 였고 전달 지연이 82초까지 쌓였다(2026-11-10 덤프). 채널의 JSON 과 앱이 받는 JSON 은 같은 모양이다.
 *
 * <p><b>밀리면 옛 신호를 버리고 최신만 보낸다.</b> 받는 대로 그 자리에서 보내면, 보내는 속도보다 오는 속도가 빠를 때 줄이 끝없이
 * 길어진다(위 수정 뒤에도 2,500명에서 지연 70초). 그래서 받는 쪽(리스너)은 신호를 칸에 넣기만 하고 돌아오고, 전송 스레드가
 * 칸에서 꺼내 보낸다. 칸은 DRAFT 면 (코스 · 정류장 · 회원), VERSION 이면 코스다 — 같은 칸에 새 신호가 오면 덮어쓴다.
 * 쌓이는 양이 "지금 편집 중인 칸 수"를 넘지 못하므로 지연이 멈춘다. DRAFT 는 최신 글만 의미가 있고 앱도 seq 로 옛 글을
 * 버리므로 건너뛴 글은 잃은 것이 아니다. 밀리지 않을 때는 넣자마자 꺼내져 그대로 다 간다.
 *
 * <p>{@link #onMessage} 는 채널을 읽는 스레드(Lettuce I/O, 하나뿐)에서 바로 불린다 — 여기서 오래 걸리면 읽기가 막힌다.
 * 그래서 파싱하고 칸에 넣는 일만 한다.
 */
@Slf4j
@Component
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class CourseSignalRelay implements MessageListener, SmartLifecycle {

	/** {@code convertAndSendToUser} 가 붙이는 것과 같은 접두사 — WebSocketConfig 의 userDestinationPrefix */
	static final String USER_PREFIX = "/user/";
	/** 소켓으로 나가는 줄(clientOutboundChannel)이 이만큼 밀려 있으면 더 넣지 않고 기다린다 — 그동안 칸에서 덮어써진다 */
	static final int OUTBOUND_BACKLOG_LIMIT = 5_000;
	/** 보내는 일은 CPU 를 쓴다(목적지 해석 · 브로커 · STOMP 인코딩) — 코어 수만큼 */
	private static final int DISPATCHERS = Math.max(2, Runtime.getRuntime().availableProcessors());

	/** VERSION 은 storeId · memberId 가 null — 코스 하나에 칸 하나 */
	private record Slot(CourseSignal.Type type, Long courseId, Long storeId, Long memberId) {
	}

	private record Pending(CourseSignal signal, byte[] body) {
	}

	private final ObjectMapper objectMapper;
	private final CourseSubscriptionRegistry registry;
	private final SimpMessagingTemplate messagingTemplate;
	private final IntSupplier outboundBacklog;
	private final Counter conflated;

	private final ConcurrentHashMap<Slot, Pending> slots = new ConcurrentHashMap<>();
	/** 보낼 것이 든 칸의 차례. 칸이 비어 있다가 채워질 때만 한 번 들어간다 */
	private final LinkedBlockingQueue<Slot> ready = new LinkedBlockingQueue<>();
	private final List<Thread> dispatchers = new ArrayList<>();
	private volatile boolean running;

	/** 생성자가 둘(테스트용)이라 Spring 이 쓸 쪽을 가리킨다. 소켓 설정이 없는 환경에는 나가는 줄 실행기가 없다 */
	@Autowired
	public CourseSignalRelay(ObjectMapper objectMapper, CourseSubscriptionRegistry registry,
			SimpMessagingTemplate messagingTemplate, MeterRegistry meterRegistry,
			@Qualifier("clientOutboundChannelExecutor") ObjectProvider<TaskExecutor> outboundExecutor) {
		this(objectMapper, registry, messagingTemplate, meterRegistry, backlogOf(outboundExecutor));
	}

	CourseSignalRelay(ObjectMapper objectMapper, CourseSubscriptionRegistry registry,
			SimpMessagingTemplate messagingTemplate, MeterRegistry meterRegistry, IntSupplier outboundBacklog) {
		this.objectMapper = objectMapper;
		this.registry = registry;
		this.messagingTemplate = messagingTemplate;
		this.outboundBacklog = outboundBacklog;
		this.conflated = Counter.builder("course_signal_conflated_total")
			.description("밀려서 더 새 신호로 덮어쓴(보내지 않은) 신호 수").register(meterRegistry);
		Gauge.builder("course_signal_pending", slots, ConcurrentHashMap::size)
			.description("전송을 기다리는 신호 칸 수").register(meterRegistry);
	}

	private static IntSupplier backlogOf(ObjectProvider<TaskExecutor> outboundExecutor) {
		TaskExecutor executor = outboundExecutor.getIfAvailable();
		if (executor instanceof ThreadPoolTaskExecutor pool) {
			return () -> pool.getThreadPoolExecutor().getQueue().size();
		}
		return () -> 0;
	}

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
		accept(signal, message.getBody());
	}

	/** REVOKE 는 바로 명단에서 뺀다. 나머지는 칸에 넣기만 한다 — 보내는 일은 전송 스레드가 한다. */
	void accept(CourseSignal signal, byte[] body) {
		if (signal.type() == CourseSignal.Type.REVOKE) {
			registry.revoke(signal.courseId(), signal.memberId());
			return;
		}
		Slot slot = new Slot(signal.type(), signal.courseId(), signal.storeId(), signal.memberId());
		Pending incoming = new Pending(signal, body);
		boolean[] wasEmpty = {false};
		slots.compute(slot, (key, waiting) -> {
			if (waiting == null) {
				wasEmpty[0] = true;
				return incoming;
			}
			conflated.increment();
			return newer(waiting, incoming);
		});
		if (wasEmpty[0]) {
			ready.add(slot);
		}
	}

	/** 서버 간 pub/sub 는 순서를 보장하지 않는다 — seq 가 큰 쪽을 남긴다. seq 가 없는 신호(VERSION)는 나중 것. */
	private static Pending newer(Pending waiting, Pending incoming) {
		Long waitingSeq = waiting.signal().seq();
		Long incomingSeq = incoming.signal().seq();
		if (waitingSeq != null && incomingSeq != null && incomingSeq < waitingSeq) {
			return waiting;
		}
		return incoming;
	}

	/**
	 * 칸 하나를 꺼내 구독 세션들에 보낸다.
	 *
	 * @return 꺼낼 칸이 있었으면 true
	 */
	boolean dispatchNext(long waitMillis) throws InterruptedException {
		Slot slot = ready.poll(waitMillis, TimeUnit.MILLISECONDS);
		if (slot == null) {
			return false;
		}
		Pending pending = slots.remove(slot);
		if (pending != null) {
			send(pending.signal(), pending.body());
		}
		return true;
	}

	/** @param body 앱에 보낼 본문 — 신호의 JSON. 구독자마다 다시 만들지 않는다 */
	private void send(CourseSignal signal, byte[] body) {
		String destination = "/queue/courses/" + signal.courseId();
		for (CourseSubscriptionRegistry.Subscriber subscriber : registry.subscribers(signal.courseId())) {
			messagingTemplate.send(USER_PREFIX + subscriber.sessionId() + destination,
				MessageBuilder.createMessage(body, sessionHeaders(subscriber.sessionId())));
		}
	}

	private void dispatchLoop() {
		while (running) {
			try {
				if (outboundBacklog.getAsInt() > OUTBOUND_BACKLOG_LIMIT) {
					Thread.sleep(2);
					continue;
				}
				dispatchNext(200);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException exception) {
				log.warn("[코스 신호] 전송 실패 — 다음 신호로 넘어간다 cause={}", exception.toString());
			}
		}
	}

	@Override
	public void start() {
		running = true;
		for (int index = 0; index < DISPATCHERS; index++) {
			Thread thread = new Thread(this::dispatchLoop, "course-signal-dispatch-" + index);
			thread.setDaemon(true);
			thread.start();
			dispatchers.add(thread);
		}
	}

	@Override
	public void stop() {
		running = false;
		dispatchers.forEach(Thread::interrupt);
		dispatchers.clear();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private static MessageHeaders sessionHeaders(String sessionId) {
		SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		accessor.setSessionId(sessionId);
		accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
		accessor.setLeaveMutable(true);
		return accessor.getMessageHeaders();
	}
}
