package sweetgirl.croissant.monthlyreport.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "monthly-report.batch")
public class MonthlyReportBatchProperties {
	@Min(1)
	@Max(1000)
	private int pageSize = 100;
	@Min(1)
	@Max(100)
	private int maxAttempts = 5;
	@Min(1)
	@Max(1440)
	private int retryDelayMinutes = 30;

	public Duration retryDelay(int attempts) {
		long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 10);
		return Duration.ofMinutes(Math.min(1440L, retryDelayMinutes * multiplier));
	}
}
