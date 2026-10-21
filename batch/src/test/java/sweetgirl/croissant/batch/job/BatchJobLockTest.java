package sweetgirl.croissant.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

class BatchJobLockTest {

	@Test
	@DisplayName("모든 @BatchJob 에 이름이 겹치지 않는 @SchedulerLock 이 있다 — v1 cron 과 겹쳐도 한 곳에서만 돈다")
	void everyBatchJobHasUniqueSchedulerLock() throws Exception {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
		List<String> missing = new ArrayList<>();
		Set<String> lockNames = new HashSet<>();
		int jobs = 0;
		for (BeanDefinition candidate : scanner.findCandidateComponents("sweetgirl.croissant")) {
			for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
				if (method.getAnnotation(BatchJob.class) == null) {
					continue;
				}
				jobs++;
				SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
				if (lock == null) {
					missing.add(method.getDeclaringClass().getSimpleName() + "." + method.getName());
					continue;
				}
				assertThat(lockNames.add(lock.name())).as("락 이름 중복: " + lock.name()).isTrue();
			}
		}

		assertThat(jobs).isPositive();
		assertThat(missing).as("@SchedulerLock 이 없는 @BatchJob").isEmpty();
	}
}
