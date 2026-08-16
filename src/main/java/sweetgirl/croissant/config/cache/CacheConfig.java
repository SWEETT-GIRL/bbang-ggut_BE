package sweetgirl.croissant.config.cache;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;

import io.lettuce.core.ClientOptions;
import sweetgirl.croissant.course.code.CourseCacheNames;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 캐시 설정.
 *
 * 원칙: 캐시 장애가 서비스 장애가 되지 않는다(fail-open). Redis가 없거나 죽어도 모든 요청은 DB로 처리되고,
 * 헬스체크·배포 판단에도 Redis 상태를 섞지 않는다({@code management.health.redis.enabled=false}).
 *
 * 캐시 목록과 무효화 규칙:
 * <ul>
 * <li>{@link CourseCacheNames#PUBLIC_LIST} — GET /courses. TTL 60초.
 * 코스 자체가 바뀌는 쓰기(어드민 큐레이션·유저 코스 명령·커버 이미지 콜백)는 {@code @CacheEvict}로 즉시 비운다.
 * 찜 수·완주자 수·작성자 닉네임·가게명처럼 "코스 밖"에서 바뀌는 값은 무효화하지 않고 TTL로 흡수한다 —
 * 쓰기 빈도가 높아 매번 비우면 히트율이 무너지고, 60초 지연은 목록 화면에서 체감되지 않는다.</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	private static final Duration COURSE_PUBLIC_LIST_TTL = Duration.ofSeconds(60);

	@Override
	public CacheErrorHandler errorHandler() {
		return new FailOpenCacheErrorHandler();
	}

	/**
	 * Redis 연결이 끊긴 동안 명령을 큐에 쌓아 타임아웃까지 기다리지 않고 즉시 실패시킨다.
	 * 그래야 fail-open이 "느린 실패"가 아니라 "즉시 DB 우회"가 된다. 재연결은 Lettuce가 백그라운드에서 계속 시도한다.
	 */
	@Bean
	public LettuceClientConfigurationBuilderCustomizer rejectCommandsWhileDisconnected() {
		return builder -> builder.clientOptions(ClientOptions.builder()
			.autoReconnect(true)
			.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
			.build());
	}

	/**
	 * 캐시별 TTL·직렬화 + 트랜잭션 인지.
	 * transactionAware: {@code @CacheEvict}를 트랜잭션 커밋 뒤로 미룬다. 커밋 전에 비우면 그 사이 들어온 조회가
	 * 옛 데이터를 다시 채워 넣는다.
	 */
	@Bean
	public RedisCacheManagerBuilderCustomizer courseCacheCustomizer() {
		return builder -> builder
			.transactionAware()
			.withCacheConfiguration(CourseCacheNames.PUBLIC_LIST, RedisCacheConfiguration.defaultCacheConfig()
				.entryTtl(COURSE_PUBLIC_LIST_TTL)
				.disableCachingNullValues()
				.serializeValuesWith(SerializationPair.fromSerializer(courseSummaryListSerializer())));
	}

	/**
	 * 값 타입을 고정한 JSON 직렬화. 제네릭 직렬화의 {@code @class} 타입 힌트 없이도 record 목록을 복원할 수 있고,
	 * redis-cli에서 값이 그대로 읽혀 운영 중 확인이 쉽다.
	 */
	static RedisSerializer<List<CourseSummaryResponse>> courseSummaryListSerializer() {
		JsonMapper jsonMapper = JsonMapper.builder().build();
		JavaType listType = jsonMapper.getTypeFactory().constructCollectionType(List.class,
			CourseSummaryResponse.class);
		return new JacksonJsonRedisSerializer<>(jsonMapper, listType);
	}
}
