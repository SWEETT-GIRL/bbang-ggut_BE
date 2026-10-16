package sweetgirl.croissant.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 모든 {@code @Scheduled} 작업에 {@code @SchedulerLock} 을 건다 — 같은 이름의 작업은 한 번에 한 곳에서만 돈다.
 *
 * <p>운영은 WAS 한 대지만 배포가 같은 서버 안 blue-green 이라, 새 컨테이너가 뜨고 옛 컨테이너가 내려가기까지 둘이 같이 떠 있다.
 * 그 사이 cron 이 걸리면 푸시가 두 번 나간다. 시각은 DB 시각({@code usingDbTime})을 써서 컨테이너 시계 차이를 없앤다.
 *
 * <p>테스트는 {@code shedlock.enabled=false} 로 끈다(H2 에 shedlock 표가 없다). 꺼지면 애노테이션은 그냥 무시된다.
 */
@Configuration
@ConditionalOnProperty(name = "shedlock.enabled", havingValue = "true", matchIfMissing = true)
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", defaultLockAtLeastFor = "PT30S")
public class SchedulerLockConfig {

	@Bean
	public LockProvider lockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
			.withJdbcTemplate(new JdbcTemplate(dataSource))
			.usingDbTime()
			.build());
	}
}
