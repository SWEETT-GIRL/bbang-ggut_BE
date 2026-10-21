package sweetgirl.croissant.store.service.search.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.store.config.StoreAutocompleteCacheProperties;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.search.StoreAutocompleteSuggestion;

/**
 * 진짜 Redis 에서 캐시·무효화가 맞게 도는지 — 목으로는 HSET/EXPIRE/SCAN/UNLINK 의 실제 동작을 못 본다.
 * 로컬 Redis(기본 localhost:6379, {@code -Dlab.redis.port} 로 변경)가 떠 있을 때 {@code ./gradlew test -PlabTests} 로 돈다.
 */
@Tag("lab")
class AutocompleteCacheRedisLabTest {

	private static final List<StoreAutocompleteSuggestion> SUGGESTIONS = List.of(
		new StoreAutocompleteSuggestion("폼포미베이커리", AutocompleteSuggestionType.STORE, 900L));

	private LettuceConnectionFactory connectionFactory;
	private StringRedisTemplate redisTemplate;
	private StoreAutocompleteCache cache;
	private AutocompleteCacheInvalidator invalidator;

	@BeforeEach
	void setUp() {
		RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
			System.getProperty("lab.redis.host", "localhost"), Integer.getInteger("lab.redis.port", 6379));
		connectionFactory = new LettuceConnectionFactory(config);
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

		StoreAutocompleteCacheProperties properties = new StoreAutocompleteCacheProperties();
		properties.setEnabled(true);
		properties.setLockWait(Duration.ofMillis(30));
		properties.setLockRetries(20);
		cache = new StoreAutocompleteCache(redisTemplate, new ObjectMapper(), properties, new SimpleMeterRegistry());
		invalidator = new AutocompleteCacheInvalidator(redisTemplate, properties);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("채운 값은 size 별 field 로 남고 7일 ±10% TTL 이 걸린다 — 두 번째 조회는 DB 를 안 간다")
	void fillsHashFieldWithTtlAndHitsNextTime() {
		AtomicInteger loads = new AtomicInteger();

		cache.getOrLoad("폼", 20, () -> load(loads));
		List<StoreAutocompleteSuggestion> second = cache.getOrLoad("폼", 20, () -> load(loads));

		assertThat(second).isEqualTo(SUGGESTIONS);
		assertThat(loads).hasValue(1);
		assertThat(redisTemplate.opsForHash().keys("ac:폼")).containsExactly("20");
		Long ttlSeconds = redisTemplate.getExpire("ac:폼");
		assertThat(ttlSeconds).isBetween(Duration.ofDays(7).toSeconds() * 9 / 10,
			Duration.ofDays(7).toSeconds() * 11 / 10);
	}

	@Test
	@DisplayName("빈 캐시에 같은 검색어 요청 20개가 동시에 와도 DB 는 한 번만 간다(스탬피드 방지)")
	void concurrentMissesLoadOnce() throws Exception {
		AtomicInteger loads = new AtomicInteger();
		Callable<List<StoreAutocompleteSuggestion>> request = () -> cache.getOrLoad("성심", 20, () -> {
			sleep(100); // 느린 DB 조회 흉내 — 그 사이 다른 요청이 몰린다
			return load(loads);
		});

		ExecutorService pool = Executors.newFixedThreadPool(20);
		try {
			List<Future<List<StoreAutocompleteSuggestion>>> results = pool.invokeAll(
				Collections.nCopies(20, request));
			for (Future<List<StoreAutocompleteSuggestion>> result : results) {
				assertThat(result.get()).isEqualTo(SUGGESTIONS);
			}
		} finally {
			pool.shutdownNow();
		}
		assertThat(loads).hasValue(1);
	}

	@Test
	@DisplayName("새 가게 이름의 부분 문자열 키만 지우고 다른 검색어 캐시는 남긴다")
	void evictByNamesRemovesOnlyAffectedKeys() {
		AtomicInteger loads = new AtomicInteger();
		cache.getOrLoad("폼", 20, () -> load(loads));
		cache.getOrLoad("포미", 10, () -> load(loads));
		cache.getOrLoad("성심", 20, () -> load(loads));

		invalidator.evictByNames(List.of("폼포미베이커리"));

		assertThat(redisTemplate.hasKey("ac:폼")).isFalse();
		assertThat(redisTemplate.hasKey("ac:포미")).isFalse();
		assertThat(redisTemplate.hasKey("ac:성심")).isTrue();
	}

	@Test
	@DisplayName("전체 비우기는 ac:* 만 지운다 — 락 키 같은 다른 키는 건드리지 않는다")
	void evictAllRemovesOnlyAutocompleteKeys() {
		AtomicInteger loads = new AtomicInteger();
		for (int i = 0; i < 1_200; i++) {
			cache.getOrLoad("검색어" + i, 20, () -> load(loads));
		}
		redisTemplate.opsForValue().set("other:key", "1");

		long evicted = invalidator.evictAll();

		assertThat(evicted).isEqualTo(1_200);
		assertThat(redisTemplate.keys("ac:*")).isEmpty();
		assertThat(redisTemplate.hasKey("other:key")).isTrue();
	}

	private static List<StoreAutocompleteSuggestion> load(AtomicInteger loads) {
		loads.incrementAndGet();
		return SUGGESTIONS;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
