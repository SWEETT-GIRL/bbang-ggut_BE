package sweetgirl.croissant.config.async;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

	/** 취향 신호 적재 전용 (#663 ④) */
	public static final String SIGNAL_EXECUTOR = "signalExecutor";

	@Override
	public Executor getAsyncExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(5);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("async-store-");

		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

		executor.initialize();
		return executor;
	}

	@Bean("reindexExecutor")
	public Executor reindexExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("reindex-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}

	/**
	 * 취향 신호(저장 · 해제 · 담기 · 병합 뒤 재계산) 전용. logExecutor 와 줄을 나누는 이유는 둘이다.
	 *
	 * <p>하나, 요청 로그는 하루 평균 18,643건(최대 232,792 · 분당 최대 2,646)인데 신호는 하루 60건이라, 같은 줄에 서면 신호가
	 * 남의 폭주에 밀려 버려진다. 둘, 해제(UNSAVE)와 담기(COURSE_ADD)는 일일 적재에 없어(찜은 하드 삭제, 담기는 협업 코스
	 * 이중 귀속 때문에 제외) 버려지면 복구할 원천이 없다.
	 *
	 * <p>그래서 넘쳐도 버리지 않고 호출 스레드가 직접 저장하며(CallerRunsPolicy — 그 요청이 INSERT 한 번만큼 늦어질 뿐이다),
	 * 배포로 내려갈 때 대기열을 비우고 종료한다. 알림 발송(pushExecutor)과 같은 대우다.
	 */
	@Bean(name = SIGNAL_EXECUTOR)
	public Executor signalExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(200);
		executor.setThreadNamePrefix("signal-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		executor.initialize();
		return executor;
	}

	// 로그 적재를 위한 가상풀 만들기 — 버려도 되는 값만(요청 로그 · 검색 미스). 잃으면 복구 못 하는 신호는 signalExecutor 로.
	@Bean("logExecutor")
	public Executor logExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(5);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("log-async-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
		executor.initialize();
		return executor;
	}
}
