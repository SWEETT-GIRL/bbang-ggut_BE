package sweetgirl.croissant.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BatchJobRegistryTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withBean(BatchJobRegistry.class);

	@Test
	@DisplayName("@BatchJob 메서드를 이름으로 찾아 실행한다")
	void findsAndRunsJobByName() {
		context.withBean(SampleJobs.class).run(result -> {
			BatchJobRegistry registry = result.getBean(BatchJobRegistry.class);
			SampleJobs jobs = result.getBean(SampleJobs.class);

			registry.find("sample-first").orElseThrow().run();

			assertThat(registry.jobs()).containsOnlyKeys("sample-first", "sample-second");
			assertThat(jobs.firstCalls.get()).isEqualTo(1);
			assertThat(jobs.secondCalls.get()).isZero();
		});
	}

	@Test
	@DisplayName("없는 이름은 빈 값으로 돌려준다")
	void unknownNameIsEmpty() {
		context.withBean(SampleJobs.class)
			.run(result -> assertThat(result.getBean(BatchJobRegistry.class).find("nope")).isEmpty());
	}

	@Test
	@DisplayName("프록시 빈을 통해 부른다 — @SchedulerLock 같은 애노테이션 기반 처리가 걸린다")
	void invokesThroughProxy() {
		AtomicInteger intercepted = new AtomicInteger();
		SampleJobs target = new SampleJobs();
		ProxyFactory factory = new ProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAdvice((MethodInterceptor)invocation -> {
			intercepted.incrementAndGet();
			return invocation.proceed();
		});

		context.withBean("sampleJobs", SampleJobs.class, () -> (SampleJobs)factory.getProxy()).run(result -> {
			result.getBean(BatchJobRegistry.class).find("sample-second").orElseThrow().run();

			assertThat(intercepted.get()).isEqualTo(1);
			assertThat(target.secondCalls.get()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("같은 이름의 작업이 둘이면 실패한다")
	void duplicateNamesFail() {
		context.withBean(SampleJobs.class).withBean(DuplicateJobs.class)
			.run(result -> assertThatThrownBy(() -> result.getBean(BatchJobRegistry.class).jobs())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("sample-first"));
	}

	static class SampleJobs {

		final AtomicInteger firstCalls = new AtomicInteger();
		final AtomicInteger secondCalls = new AtomicInteger();

		@BatchJob("sample-first")
		public void first() {
			firstCalls.incrementAndGet();
		}

		@BatchJob("sample-second")
		public void second() {
			secondCalls.incrementAndGet();
		}
	}

	static class DuplicateJobs {

		@BatchJob("sample-first")
		public void again() {}
	}
}
