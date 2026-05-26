package sweetgirl.croissant.admin.bulkimport.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class BulkImportAsyncConfig {

	public static final String EXECUTOR_NAME = "bulkImportExecutor";

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
}
