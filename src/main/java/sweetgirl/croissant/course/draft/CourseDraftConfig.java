package sweetgirl.croissant.course.draft;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 초안 자동저장 설정 묶음. 스위치(CourseDraftProperties)는 늘 뜨고, 초안 Redis 연결은 {@code course.draft.redis.mode} 가 있을 때만 만든다.
 * 초안 컴포넌트(버퍼·플러셔·API)도 같은 조건을 달아 Redis 없는 환경에서는 아예 뜨지 않는다.
 */
@Configuration
@EnableConfigurationProperties(CourseDraftProperties.class)
public class CourseDraftConfig {

	public static final String REDIS_MODE_PROPERTY = "course.draft.redis.mode";

	/**
	 * 초안 빈의 조건. {@code @ConditionalOnProperty(name = ...)} 는 빈 문자열도 "있다"로 본다 — compose 가
	 * {@code COURSE_DRAFT_REDIS_MODE: ${...:-}} 로 빈 값을 넘기면 초안 빈이 전부 떠서 없는 Redis 를 찌른다. 그래서 "비어 있지 않음"을 식으로 본다.
	 */
	public static final String REDIS_CONFIGURED = "!'${course.draft.redis.mode:}'.isBlank()";

	@Bean(destroyMethod = "close")
	@ConditionalOnExpression(REDIS_CONFIGURED)
	public MemoDraftRedis memoDraftRedis(CourseDraftProperties properties) {
		return MemoDraftRedis.connect(properties.getRedis());
	}
}
