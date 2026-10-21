package sweetgirl.croissant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

class SchedulerLockConfigTest {

	@Test
	@DisplayName("모든 @Scheduled 작업에 이름이 겹치지 않는 @SchedulerLock 이 있다 — 새 작업이 락 없이 들어오면 여기서 깨진다")
	void everyScheduledMethodHasUniqueSchedulerLock() throws Exception {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
		List<String> missing = new ArrayList<>();
		Set<String> names = new HashSet<>();
		int scheduled = 0;
		for (BeanDefinition candidate : scanner.findCandidateComponents("sweetgirl.croissant")) {
			for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
				if (AnnotatedElementUtils.findMergedRepeatableAnnotations(method, Scheduled.class).isEmpty()) {
					continue;
				}
				scheduled++;
				SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
				if (lock == null) {
					missing.add(method.getDeclaringClass().getSimpleName() + "." + method.getName());
					continue;
				}
				assertThat(names.add(lock.name())).as("락 이름 중복: " + lock.name()).isTrue();
			}
		}

		assertThat(scheduled).isPositive();
		assertThat(missing).as("@SchedulerLock 이 없는 @Scheduled 작업").isEmpty();
	}

	@Test
	@DisplayName("락을 쥔 동안 같은 이름은 못 잡고, 풀리면 다시 잡는다 (DB 시각 기준)")
	void lockProvider_excludesConcurrentHolders() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:shedlock-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
		new JdbcTemplate(dataSource).execute("""
				CREATE TABLE shedlock (name VARCHAR(64) NOT NULL PRIMARY KEY, lock_until TIMESTAMP NOT NULL,
				locked_at TIMESTAMP NOT NULL, locked_by VARCHAR(255) NOT NULL)""");
		LockProvider lockProvider = new SchedulerLockConfig().lockProvider(dataSource);
		LockConfiguration config = new LockConfiguration(Instant.now(), "RewardPushScheduler_attendanceReminders",
			Duration.ofMinutes(10), Duration.ZERO);

		Optional<SimpleLock> first = lockProvider.lock(config);
		Optional<SimpleLock> second = lockProvider.lock(config);
		first.orElseThrow().unlock();
		Optional<SimpleLock> afterUnlock = lockProvider.lock(config);

		assertThat(first).isPresent();
		assertThat(second).isEmpty();
		assertThat(afterUnlock).isPresent();
	}
}
