package sweetgirl.croissant.alarm.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 푸시 발송 전용 스레드 풀.
 *
 * <p>공용 풀을 쓰지 않는 이유는 격리다. 공지 한 번이 수천 건의 FCM 왕복을 만드는데, 그것이
 * 다른 비동기 작업과 같은 풀에 앉으면 공지를 보내는 동안 나머지가 전부 밀린다.
 *
 * <p>큐가 차면 부른 스레드가 직접 처리한다(CallerRuns). 버리는 쪽이 아니라 느려지는 쪽을 고른 것이다 —
 * 알림은 늦게 가도 되지만 조용히 사라지면 안 된다.
 */
@Configuration
@EnableAsync
public class PushAsyncConfig {

	public static final String PUSH_EXECUTOR = "pushExecutor";

	@Bean(name = PUSH_EXECUTOR)
	public Executor pushExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("push-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		// 종료 중 발송이 잘리면 우편함에는 있는데 푸시만 안 간 알림이 남는다. 끝날 때까지 기다린다.
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.initialize();
		return executor;
	}
}
