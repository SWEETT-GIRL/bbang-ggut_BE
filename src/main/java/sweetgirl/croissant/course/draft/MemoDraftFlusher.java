package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 1초마다 반영 시각이 된 코스를 꺼내 DB 에 반영하는 플러셔.
 *
 * <p>인스턴스마다 돌고 ShedLock 은 없다 — 꺼내기가 원자적이라 여러 대가 나눠 가져도 같은 코스가 두 번 나오지 않고, 임대가 지나
 * 두 대가 같은 코스를 잡아도 코스 행 락이 줄 세우고 seq 가 거른다. 전용 스레드 하나로 돈다: 기본 {@code @Scheduled} 풀은 단일
 * 스레드를 스케줄러 열 개가 나눠 써서 긴 배치 뒤에 줄 서면 10초 상한이 깨진다. {@code TaskScheduler} 빈으로 두지 않는 이유는
 * Boot 가 그 빈을 모든 {@code @Scheduled} 의 스케줄러로 삼기 때문이다.
 *
 * <p>종료 중에는 새 코스를 꺼내지 않고, 이미 꺼낸 코스는 끝까지 반영한다. 그래도 남은 임대는 다른 인스턴스의 회수가 처리한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = CourseDraftConfig.REDIS_MODE_PROPERTY)
public class MemoDraftFlusher implements SmartLifecycle {

	static final Duration TICK = Duration.ofSeconds(1);

	private final CourseDraftProperties properties;
	private final MemoDraftBuffer buffer;
	private final MemoDraftApplier applier;

	private ThreadPoolTaskScheduler scheduler;
	private volatile boolean running;
	private volatile boolean stopping;

	public MemoDraftFlusher(CourseDraftProperties properties, MemoDraftBuffer buffer, MemoDraftApplier applier) {
		this.properties = properties;
		this.buffer = buffer;
		this.applier = applier;
	}

	@Override
	public void start() {
		stopping = false;
		scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("draft-flusher-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(10);
		scheduler.initialize();
		scheduler.scheduleWithFixedDelay(this::tick, TICK);
		running = true;
		log.info("[메모 초안] 플러셔 시작 — {}마다 꺼낸다, flusher-enabled={}", TICK, properties.isFlusherEnabled());
	}

	@Override
	public void stop() {
		stopping = true;
		running = false;
		if (scheduler != null) {
			scheduler.shutdown();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** 한 틱. 스위치가 꺼져 있으면 아무것도 안 한다. Redis 가 안 되면 이번 틱은 건너뛴다 — 초안은 Redis 에 그대로 남아 있다. */
	void tick() {
		if (!properties.isFlusherEnabled()) {
			return;
		}
		long now = System.currentTimeMillis();
		try {
			long reclaimed = buffer.reclaimExpired(now);
			if (reclaimed > 0) {
				log.warn("[메모 초안] 임대가 만료된 코스 {}건을 되돌렸다 — 플러셔가 죽었거나 반영이 {}를 넘겼다", reclaimed,
					properties.getLease());
			}
			List<Long> courseIds = buffer.popDue(now);
			for (Long courseId : courseIds) {
				if (stopping) {
					// 종료 중 — 바로 due 로 돌려 다른 인스턴스가 가져가게 한다
					buffer.release(courseId, Map.of(), System.currentTimeMillis(), Duration.ZERO);
					continue;
				}
				flushCourse(courseId);
			}
		} catch (DataAccessException exception) {
			log.warn("[메모 초안] Redis 접근 실패 — 다음 틱에 다시 cause={}", exception.toString());
		}
	}

	/** 코스 하나 = 트랜잭션 하나. DB 반영이 실패하면 전부 남기고 retry-delay 뒤 다시 꺼내게 한다. */
	void flushCourse(long courseId) {
		List<DraftEntry> entries = buffer.readAll(courseId);
		Map<DraftField, Long> handled;
		try {
			handled = applier.apply(courseId, entries);
		} catch (RuntimeException exception) {
			log.error("[메모 초안] DB 반영 실패 — {} 뒤 다시 courseId={} drafts={}", properties.getRetryDelay(), courseId,
				entries.size(), exception);
			buffer.release(courseId, Map.of(), System.currentTimeMillis(), properties.getRetryDelay());
			return;
		}
		long remaining = buffer.release(courseId, handled, System.currentTimeMillis(), properties.getQuietWindow());
		log.debug("[메모 초안] 반영 courseId={} applied={} remaining={}", courseId, handled.size(), remaining);
	}
}
