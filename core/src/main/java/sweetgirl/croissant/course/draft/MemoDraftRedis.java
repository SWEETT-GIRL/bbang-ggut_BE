package sweetgirl.croissant.course.draft;

import java.time.Duration;

import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;

/**
 * 초안 전용 Redis 연결.
 *
 * <p>{@link org.springframework.data.redis.connection.RedisConnectionFactory}·{@link StringRedisTemplate} 타입의 빈을
 * 하나 더 두면 Boot 의 캐시 Redis 자동 구성이 물러나 자동완성·급상승이 깨진다. 그래서 연결 팩토리와 템플릿을 이 홀더 안에
 * 감춰 빈 타입이 겹치지 않게 한다.
 * 읽기는 항상 마스터에서 한다 — 레플리카의 낡은 seq 로 비교하면 역전이 생긴다. 그래서 {@code readFrom} 을 **지정하지 않는다**:
 * 지정하지 않은 Sentinel 연결은 감시자가 알려 준 마스터 하나에만 붙고, 끊기면 재연결할 때마다 감시자에게 마스터를 다시 묻는다.
 * {@code readFrom(MASTER)} 를 주면 Spring 이 Lettuce 의 MasterReplica 연결(토폴로지 캐시)을 쓰는데, 이 연결은 감시자 이벤트를
 * 받고도 수 초 뒤에야 토폴로지를 갱신한다 — 운영 페일오버 측정(2026-11-09)에서 승격 뒤에도 약 3.5초 동안 옛 마스터 주소로
 * 명령을 보내 {@code Connection reset} 503 이 이어진 원인이다.
 *
 * <p>재연결 간격은 짧게 고정한다. Lettuce 기본은 지수 백오프라 마스터가 죽은 뒤 몇 번 실패하면 다음 시도까지 몇 초를
 * 기다린다 — 운영 페일오버 측정(2026-11-09)에서 승격은 5.6초에 끝났는데 앱은 9초 만에 새 마스터에 붙었다(재시도가
 * +2.0 · +7.1 · +8.7초). 간격을 고정하면 승격 직후 바로 붙는다. Sentinel 모드는 시도마다 감시자에게 마스터를 다시 묻는다.
 */
public class MemoDraftRedis implements AutoCloseable {

	private final ClientResources clientResources;
	private final LettuceConnectionFactory connectionFactory;
	private final StringRedisTemplate template;

	private MemoDraftRedis(ClientResources clientResources, LettuceConnectionFactory connectionFactory) {
		this.clientResources = clientResources;
		this.connectionFactory = connectionFactory;
		this.template = new StringRedisTemplate(connectionFactory);
		this.template.afterPropertiesSet();
	}

	public static MemoDraftRedis connect(CourseDraftProperties.Redis redis) {
		ClientResources clientResources = clientResourcesOf(redis);
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
			.clientResources(clientResources)
			.commandTimeout(redis.getCommandTimeout())
			.build();
		LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configurationOf(redis),
			clientConfiguration);
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		return new MemoDraftRedis(clientResources, connectionFactory);
	}

	/** 캐시 Redis 의 공유 리소스와 섞지 않는다 — 재연결 간격은 초안 연결에만 적용한다. */
	static ClientResources clientResourcesOf(CourseDraftProperties.Redis redis) {
		Duration delay = redis.getReconnectDelay();
		return ClientResources.builder()
			.reconnectDelay(Delay.constant(delay))
			.build();
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
		clientResources.shutdown();
	}
}
