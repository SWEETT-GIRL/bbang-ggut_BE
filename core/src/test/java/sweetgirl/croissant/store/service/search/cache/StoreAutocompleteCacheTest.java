package sweetgirl.croissant.store.service.search.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.store.config.StoreAutocompleteCacheProperties;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.search.StoreAutocompleteSuggestion;

@ExtendWith(MockitoExtension.class)
class StoreAutocompleteCacheTest {

	private static final String QUERY = "성심";
	private static final String KEY = "ac:성심";
	private static final String LOCK_KEY = "lock:ac:성심";
	private static final List<StoreAutocompleteSuggestion> SUGGESTIONS = List.of(
		new StoreAutocompleteSuggestion("성심당 본점", AutocompleteSuggestionType.STORE, 75L));

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private HashOperations<String, Object, Object> hashOperations;
	@Mock
	private ValueOperations<String, String> valueOperations;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final StoreAutocompleteCacheProperties properties = new StoreAutocompleteCacheProperties();
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final AtomicInteger loaderCalls = new AtomicInteger();
	private StoreAutocompleteCache cache;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		properties.setLockWait(Duration.ZERO);
		cache = new StoreAutocompleteCache(redisTemplate, objectMapper, properties, meterRegistry);
		cache.registerCounters();
	}

	private Supplier<List<StoreAutocompleteSuggestion>> loader() {
		return () -> {
			loaderCalls.incrementAndGet();
			return SUGGESTIONS;
		};
	}

	private String json(List<StoreAutocompleteSuggestion> suggestions) throws Exception {
		return objectMapper.writeValueAsString(suggestions);
	}

	private double count(String result) {
		return meterRegistry.get(StoreAutocompleteCache.METRIC).tag("result", result).counter().count();
	}

	@Test
	@DisplayName("꺼져 있으면 Redis 를 건드리지 않고 매번 DB 를 조회한다")
	void disabled_bypassesRedis() {
		properties.setEnabled(false);

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
		assertThat(loaderCalls).hasValue(1);
		verifyNoInteractions(redisTemplate);
	}

	@Test
	@DisplayName("캐시에 있으면 DB 를 조회하지 않고 그대로 돌려준다")
	void hit_returnsCachedWithoutLoading() throws Exception {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(hashOperations.get(KEY, "20")).willReturn(json(SUGGESTIONS));

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
		assertThat(loaderCalls).hasValue(0);
		assertThat(count("hit")).isEqualTo(1);
	}

	@Test
	@DisplayName("비어 있으면 락을 잡은 요청이 DB 를 조회해 size 별 field 에 채우고, 7일 ±10% TTL 을 건다")
	void miss_loadsAndFillsWithJitteredTtl() throws Exception {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(3))).willReturn(true);

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
		assertThat(loaderCalls).hasValue(1);
		verify(hashOperations).put(KEY, "20", json(SUGGESTIONS));
		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(redisTemplate).expire(eq(KEY), ttl.capture());
		assertThat(ttl.getValue()).isBetween(Duration.ofDays(7).multipliedBy(9).dividedBy(10),
			Duration.ofDays(7).multipliedBy(11).dividedBy(10));
		assertThat(count("miss")).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 요청이 채우는 중이면 기다렸다가 그 결과를 받는다 — 같은 검색어로 DB 에 몰리지 않는다(스탬피드 방지)")
	void lockHeldByOther_waitsAndReusesFilledValue() throws Exception {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(3))).willReturn(false);
		given(hashOperations.get(KEY, "20")).willReturn(null, null, json(SUGGESTIONS));

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
		assertThat(loaderCalls).hasValue(0);
		assertThat(count("coalesced")).isEqualTo(1);
	}

	@Test
	@DisplayName("기다려도 안 채워지면(채우던 요청이 실패) 직접 DB 를 조회한다 — 무한정 기다리지 않는다")
	void lockHeldByOther_neverFilled_loadsDirectly() {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(3))).willReturn(false);

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
		assertThat(loaderCalls).hasValue(1);
		// 첫 조회 + 재시도 3번
		verify(hashOperations, times(1 + properties.getLockRetries())).get(KEY, "20");
	}

	@Test
	@DisplayName("Redis 가 죽어 있으면 예외 없이 DB 결과를 돌려준다 — 캐시는 없어도 되는 기능이다")
	void redisDown_fallsBackToLoader() {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(hashOperations.get(anyString(), any())).willThrow(new RedisConnectionFailureException("down"));

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
		assertThat(loaderCalls).hasValue(1);
		assertThat(count("error")).isEqualTo(1);
	}

	@Test
	@DisplayName("채우기에 실패해도 이미 조회한 결과는 돌려준다")
	void writeFailure_stillReturnsLoaded() {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(3))).willReturn(true);
		willThrow(new RedisConnectionFailureException("down")).given(hashOperations)
			.put(anyString(), any(), any());

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
	}

	@Test
	@DisplayName("DB 조회가 타임아웃이면 그대로 던지고 아무것도 캐시하지 않는다 — 빈 결과가 7일 동안 남지 않게")
	void loaderFailure_propagatesAndCachesNothing() {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(3))).willReturn(true);

		assertThatThrownBy(() -> cache.getOrLoad(QUERY, 20, () -> {
			throw new QueryTimeoutException("slow");
		})).isInstanceOf(QueryTimeoutException.class);
		verify(hashOperations, never()).put(anyString(), any(), any());
	}

	@Test
	@DisplayName("읽을 수 없는 옛 값은 미스로 보고 다시 채운다 — 응답 모양이 바뀐 뒤에도 깨지지 않는다")
	void unreadableValue_isTreatedAsMiss() {
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(hashOperations.get(KEY, "20")).willReturn("{not-json");
		given(valueOperations.setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(3))).willReturn(true);

		List<StoreAutocompleteSuggestion> result = cache.getOrLoad(QUERY, 20, loader());

		assertThat(result).isEqualTo(SUGGESTIONS);
		assertThat(loaderCalls).hasValue(1);
	}

	@Test
	@DisplayName("TTL 은 늘 ±비율 안에서 흔들리고, 비율이 0 이면 그대로다")
	void jitteredTtl_staysWithinRatio() {
		Duration base = Duration.ofDays(7);
		for (int i = 0; i < 1_000; i++) {
			assertThat(cache.jitteredTtl()).isBetween(base.multipliedBy(9).dividedBy(10),
				base.multipliedBy(11).dividedBy(10));
		}
		properties.setTtlJitterRatio(0);
		assertThat(cache.jitteredTtl()).isEqualTo(base);
	}
}
