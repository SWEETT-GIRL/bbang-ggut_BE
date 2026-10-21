package sweetgirl.croissant.batch.job;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import lombok.RequiredArgsConstructor;

/**
 * 컨텍스트의 빈에서 {@link BatchJob} 메서드를 모아 이름으로 찾는다.
 *
 * <p>메서드는 프록시 빈을 통해 부른다 — 그래야 {@code @SchedulerLock}·{@code @Transactional} 이 그대로 걸린다.
 */
@Component
@RequiredArgsConstructor
public class BatchJobRegistry {

	private static final String APPLICATION_PACKAGE = "sweetgirl.croissant.";

	private final ApplicationContext context;

	public Optional<Runnable> find(String name) {
		return Optional.ofNullable(jobs().get(name));
	}

	public Map<String, Runnable> jobs() {
		Map<String, Runnable> jobs = new TreeMap<>();
		for (String beanName : context.getBeanDefinitionNames()) {
			Class<?> beanType = context.getType(beanName);
			if (beanType == null) {
				continue;
			}
			Class<?> userType = ClassUtils.getUserClass(beanType);
			if (!userType.getName().startsWith(APPLICATION_PACKAGE)) {
				continue;
			}
			Map<Method, BatchJob> methods = MethodIntrospector.selectMethods(userType,
				(MethodIntrospector.MetadataLookup<BatchJob>)method -> AnnotatedElementUtils.findMergedAnnotation(
					method,
					BatchJob.class));
			methods.forEach((method, job) -> register(jobs, job.value(), beanName, method));
		}
		return jobs;
	}

	private void register(Map<String, Runnable> jobs, String name, String beanName, Method method) {
		Object bean = context.getBean(beanName);
		Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
		ReflectionUtils.makeAccessible(invocable);
		Runnable previous = jobs.putIfAbsent(name, () -> ReflectionUtils.invokeMethod(invocable, bean));
		if (previous != null) {
			throw new IllegalStateException("배치 작업 이름이 겹친다: " + name);
		}
	}
}
