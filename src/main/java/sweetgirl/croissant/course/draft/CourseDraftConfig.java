package sweetgirl.croissant.course.draft;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = REDIS_MODE_PROPERTY)
	public MemoDraftRedis memoDraftRedis(CourseDraftProperties properties) {
		return MemoDraftRedis.connect(properties.getRedis());
	}
}
