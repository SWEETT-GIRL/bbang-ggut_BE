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

	/** 급부상 신호(Redis 카운트) 기록 전용 — 넘치면 버린다 */
	public static final String TRENDING_EXECUTOR = "trendingExecutor";

	/** 이유 문장 LLM 호출 전용 (#764) */
	public static final String REASON_LLM_EXECUTOR = "reasonLlmExecutor";

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

	/**
	 * 급부상 신호를 Redis 에 세는 실행기. 상세 조회마다 불려 가장 잦은 신호다.
	 *
	 * <p>signalExecutor 와 달리 넘치면 버린다(DiscardPolicy) — 순위표는 수백 건 중 몇 건이 빠져도 같고, 요청 스레드가 Redis 를 대신
	 * 기다리게 하는 편이 더 나쁘다.
	 */
	@Bean(name = TRENDING_EXECUTOR)
	public Executor trendingExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("trending-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
		executor.initialize();
		return executor;
	}

	/**
	 * 이유 문장 LLM 호출 전용 (#764). 요청 스레드가 직접 부르지 않고 여기로 던지는 이유는 하나다 — <b>기다림을 끊기
	 * 위해서다.</b> 벤더 클라이언트의 읽기 타임아웃은 60초인데 사용자는 그만큼 못 기다린다. 호출을 이 줄에 맡기고 요청
	 * 스레드는 설정한 시간(기본 4초)만 기다린 뒤 룰 문장으로 답한다.
	 *
	 * <p>줄을 세우지 않는다(queueCapacity = 0). 대기열에서 차례를 기다린 호출은 이미 기다림 예산을 다 쓴 뒤라 어차피
	 * 버려진다. 자리가 없으면 바로 거절(AbortPolicy)하고 부르는 쪽이 룰 문장으로 넘어간다.
	 *
	 * <p>스레드가 4개인 것은 <b>끊긴 호출이 스레드를 놓지 않기 때문이다</b> — 기다림을 끊어도 그 HTTP 읽기는 벤더
	 * 타임아웃까지 계속된다. 넷이 물려 있는 동안 들어온 요청은 기다리지 않고 룰 문장을 받는다.
	 */
	@Bean(name = REASON_LLM_EXECUTOR)
	public Executor reasonLlmExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(0);
		executor.setThreadNamePrefix("reason-llm-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
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
