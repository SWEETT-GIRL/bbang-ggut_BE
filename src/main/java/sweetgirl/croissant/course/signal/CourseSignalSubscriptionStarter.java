package sweetgirl.croissant.course.signal;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.draft.CourseDraftConfig;

/**
 * Redis pub/sub 리스너 컨테이너를 앱 기동과 분리해 시작한다.
 *
 * <p>컨테이너를 그대로 두면 기동 시점에 초안 Redis 에 못 붙을 때 {@code Failed to start bean 'courseSignalListenerContainer'} 로
 * **앱 전체가 안 뜬다**(2026-11-04 dev 배포에서 발생). 초안·신호는 없어도 서비스가 돌아야 하는 부가 기능이라, 실패하면 경고만 남기고
 * 10초마다 다시 붙는다. 붙기 전까지 신호는 못 받지만 앱의 폴링이 메운다.
 */
@Slf4j
@Component
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class CourseSignalSubscriptionStarter implements SmartLifecycle {

	static final Duration RETRY = Duration.ofSeconds(10);

	private final RedisMessageListenerContainer container;
	private ThreadPoolTaskScheduler scheduler;
	private volatile boolean running;

	public CourseSignalSubscriptionStarter(RedisMessageListenerContainer courseSignalListenerContainer) {
		this.container = courseSignalListenerContainer;
	}

	@Override
	public void start() {
		running = true;
		scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("course-signal-sub-");
		scheduler.initialize();
		scheduler.scheduleWithFixedDelay(this::tryStart, RETRY);
		tryStart();
	}

	/** 한 번 시도. 실패해도 던지지 않는다 — 다음 주기에 다시. */
	void tryStart() {
		if (!running || container.isRunning()) {
			return;
		}
		try {
			container.start();
			log.info("[코스 신호] Redis 채널 구독 시작");
		} catch (RuntimeException exception) {
			log.warn("[코스 신호] Redis 채널 구독 실패 — {} 뒤 다시. 그동안 신호는 폴링이 메운다 cause={}", RETRY,
				exception.toString());
		}
	}

	@Override
	public void stop() {
		running = false;
		if (scheduler != null) {
			scheduler.shutdown();
		}
		if (container.isRunning()) {
			container.stop();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
