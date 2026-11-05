package sweetgirl.croissant.course.draft;

import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import io.lettuce.core.ReadFrom;

/**
 * 초안 전용 Redis 연결.
 *
 * <p>{@link org.springframework.data.redis.connection.RedisConnectionFactory}·{@link StringRedisTemplate} 타입의 빈을
 * 하나 더 두면 Boot 의 캐시 Redis 자동 구성이 물러나 자동완성·급상승이 깨진다. 그래서 연결 팩토리와 템플릿을 이 홀더 안에
 * 감춰 빈 타입이 겹치지 않게 한다.
 * 읽기는 항상 마스터에서 한다 — 레플리카의 낡은 seq 로 비교하면 역전이 생긴다.
 */
public class MemoDraftRedis implements AutoCloseable {

	private final LettuceConnectionFactory connectionFactory;
	private final StringRedisTemplate template;

	private MemoDraftRedis(LettuceConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
		this.template = new StringRedisTemplate(connectionFactory);
		this.template.afterPropertiesSet();
	}

	public static MemoDraftRedis connect(CourseDraftProperties.Redis redis) {
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
			.readFrom(ReadFrom.MASTER)
			.commandTimeout(redis.getCommandTimeout())
			.build();
		LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configurationOf(redis),
			clientConfiguration);
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		return new MemoDraftRedis(connectionFactory);
	}

	private static RedisConfiguration configurationOf(CourseDraftProperties.Redis redis) {
		RedisPassword password = StringUtils.hasText(redis.getPassword())
			? RedisPassword.of(redis.getPassword())
			: RedisPassword.none();
		if (redis.getMode() == CourseDraftProperties.Mode.SENTINEL) {
			RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration();
			sentinel.master(redis.getSentinelMaster());
			redis.getSentinelNodes().forEach(node -> sentinel.sentinel(RedisNode.fromString(node)));
			sentinel.setPassword(password);
			sentinel.setDatabase(redis.getDatabase());
			return sentinel;
		}
		RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
		standalone.setPassword(password);
		standalone.setDatabase(redis.getDatabase());
		return standalone;
	}

	public StringRedisTemplate template() {
		return template;
	}

	/** pub/sub 리스너 컨테이너용. 빈으로 노출하지 않는다 — 캐시 Redis 자동 구성이 물러나지 않게. */
	public LettuceConnectionFactory connectionFactory() {
		return connectionFactory;
	}

	@Override
	public void close() {
		connectionFactory.destroy();
	}
}
