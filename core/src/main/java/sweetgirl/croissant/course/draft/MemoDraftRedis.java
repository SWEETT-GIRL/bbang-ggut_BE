package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class MemoDraftRedis implements AutoCloseable {

	static final long MIN_RESET_INTERVAL_MILLIS = 2_000L;

	private final ClientResources clientResources;
	private final LettuceConnectionFactory connectionFactory;
	private final StringRedisTemplate template;
	private final int resetAfterTimeouts;
	private final AtomicInteger consecutiveTimeouts = new AtomicInteger();
	private final AtomicBoolean resetting = new AtomicBoolean();
	private volatile long lastResetAtMillis;

	MemoDraftRedis(ClientResources clientResources, LettuceConnectionFactory connectionFactory,
			int resetAfterTimeouts) {
		this.clientResources = clientResources;
		this.connectionFactory = connectionFactory;
		this.resetAfterTimeouts = resetAfterTimeouts;
		this.template = new StringRedisTemplate(connectionFactory);
		this.template.afterPropertiesSet();
	}

	public static MemoDraftRedis connect(CourseDraftProperties.Redis redis) {
		ClientResources clientResources = clientResourcesOf(redis);
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
			.clientResources(clientResources)
			.clientOptions(clientOptionsOf(redis))
			.commandTimeout(redis.getCommandTimeout())
			.build();
		LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configurationOf(redis),
			clientConfiguration);
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		return new MemoDraftRedis(clientResources, connectionFactory, redis.getResetAfterTimeouts());
	}

	/** 캐시 Redis 의 공유 리소스와 섞지 않는다 — 재연결 간격은 초안 연결에만 적용한다. */
	static ClientResources clientResourcesOf(CourseDraftProperties.Redis redis) {
		Duration delay = redis.getReconnectDelay();
		return ClientResources.builder()
			.reconnectDelay(Delay.constant(delay))
			.build();
	}

	/** 끊긴 동안의 명령 — 쌓아 둘지(기본) 바로 거절할지. 자동 재연결은 어느 쪽이든 켜 둔다. */
	static ClientOptions clientOptionsOf(CourseDraftProperties.Redis redis) {
		return ClientOptions.builder()
			.autoReconnect(true)
			.disconnectedBehavior(redis.isRejectWhenDisconnected()
				? ClientOptions.DisconnectedBehavior.REJECT_COMMANDS
				: ClientOptions.DisconnectedBehavior.DEFAULT)
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

	/** 명령이 성공했다 — 연달아 난 타임아웃 세기를 지운다. PUT 마다 불리므로 0 이 아닐 때만 쓴다. */
	public void commandSucceeded() {
		if (consecutiveTimeouts.get() != 0) {
			consecutiveTimeouts.set(0);
		}
	}

	/**
	 * 명령이 타임아웃 났다. 성공 없이 {@code reset-after-timeouts} 번 연달아 나면 연결을 끊고 다시 맺게 한다 — 멈춘 Redis 에
	 * 붙어 있는 연결은 스스로 끊기지 않는다. 한 번에 한 스레드만, {@link #MIN_RESET_INTERVAL_MILLIS} 에 한 번만 한다.
	 */
	public void commandTimedOut() {
		if (resetAfterTimeouts <= 0 || consecutiveTimeouts.incrementAndGet() < resetAfterTimeouts) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastResetAtMillis < MIN_RESET_INTERVAL_MILLIS || !resetting.compareAndSet(false, true)) {
			return;
		}
		try {
			lastResetAtMillis = now;
			consecutiveTimeouts.set(0);
			log.warn("[메모 초안] Redis 명령 타임아웃이 {}번 연달아 났다 — 연결을 끊고 다시 맺는다(감시자에게 마스터를 다시 묻는다)",
				resetAfterTimeouts);
			connectionFactory.resetConnection();
		} catch (RuntimeException exception) {
			log.warn("[메모 초안] Redis 연결 다시 맺기 실패 cause={}", exception.toString());
		} finally {
			resetting.set(false);
		}
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
