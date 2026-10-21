package sweetgirl.croissant.batch.job;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 배치 EC2 에서 이름으로 실행하는 작업. {@code --batch.job=<value>} 로 고른다.
 *
 * <p>언제 돌지는 코드가 아니라 EventBridge 규칙이 정한다(EC2 를 켜는 시각). 같은 작업이 v1 WAS 의 cron 과 겹쳐도
 * 한 곳에서만 돌도록 메서드에 {@code @SchedulerLock} 을 함께 단다 — 락 이름은 기존 스케줄러와 같아야 한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BatchJob{

	/** 작업 이름(kebab-case). EventBridge·Lambda 가 이 이름으로 실행한다 */
	String value();
}
