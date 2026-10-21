package sweetgirl.croissant.batch.job;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code --batch.job=<이름>} 으로 받은 작업 하나를 실행한다. 이름이 없거나 틀리면 실패로 끝나 Lambda 가 알 수 있게 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "batch.job")
public class BatchJobRunner implements ApplicationRunner {

	private final BatchJobRegistry registry;
	private final String jobName;

	public BatchJobRunner(BatchJobRegistry registry, @Value("${batch.job}") String jobName) {
		this.registry = registry;
		this.jobName = jobName;
	}

	@Override
	public void run(ApplicationArguments args) {
		Runnable job = registry.find(jobName)
			.orElseThrow(() -> new IllegalArgumentException(
				"알 수 없는 배치 작업: " + jobName + " (가능한 작업: " + registry.jobs().keySet() + ")"));
		Instant startedAt = Instant.now();
		log.info("[배치] 시작 job={}", jobName);
		job.run();
		log.info("[배치] 끝 job={} elapsedMs={}", jobName, Duration.between(startedAt, Instant.now()).toMillis());
	}
}
