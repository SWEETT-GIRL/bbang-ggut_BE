package sweetgirl.croissant.admin.bulkimport.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import sweetgirl.croissant.admin.bulkimport.code.BulkImportRateLimiter;

@Configuration
public class BulkImportAsyncConfig {

	public static final String EXECUTOR_NAME = "bulkImportExecutor";

	// Kakao 무료 한도 월 300만 호출 — 200ms (초당 5건) 페이스로도 월 8% 미만이라 안전
	private static final long KAKAO_MIN_INTERVAL_MS = 200L;

	@Bean(name = EXECUTOR_NAME)
	public Executor bulkImportExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("bulk-import-");
		executor.initialize();
		return executor;
	}

	@Bean
	public BulkImportRateLimiter bulkImportRateLimiter() {
		return new BulkImportRateLimiter(KAKAO_MIN_INTERVAL_MS);
	}
}
