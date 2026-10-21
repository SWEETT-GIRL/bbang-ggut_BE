package sweetgirl.croissant.breadlog.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;

/**
 * 사진 분석 전용 스레드풀.
 *
 * <p>공용 ForkJoinPool 이면 안 되는 이유 — 크기가 (코어 수 - 1) 이라 2코어 서버에서는 1이고,
 * 사진마다 건 {@code orTimeout} 은 예약 시점부터 재므로 차례를 기다리던 사진이 Gemini 를
 * 불러보지도 못한 채 타임아웃으로 버려진다.
 *
 * <p>큐를 두지 않는다(SynchronousQueue). 기다리게 하는 건 어차피 타임아웃될 자리를 만드는 것이고,
 * 자리가 없으면 그 사진만 빈 결과로 두는 편이 낫다.
 */
@Configuration
@RequiredArgsConstructor
public class BreadPhotoTagExecutorConfig {

	private static final long KEEP_ALIVE_SECONDS = 60L;

	private final BreadPhotoTagProperties properties;

	@Bean(destroyMethod = "shutdown")
	public ExecutorService breadPhotoTagExecutor() {
		AtomicInteger sequence = new AtomicInteger();
		return new ThreadPoolExecutor(
			0,
			properties.getMaxConcurrentPhotos(),
			KEEP_ALIVE_SECONDS,
			TimeUnit.SECONDS,
			new SynchronousQueue<>(),
			runnable -> {
				Thread thread = new Thread(runnable, "bread-photo-tag-" + sequence.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			});
	}
}
