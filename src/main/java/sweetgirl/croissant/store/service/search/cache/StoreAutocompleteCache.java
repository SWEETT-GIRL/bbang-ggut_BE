package sweetgirl.croissant.store.service.search.cache;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreAutocompleteCacheProperties;
import sweetgirl.croissant.store.service.search.StoreAutocompleteSuggestion;

/**
 * 자동완성 결과의 Redis 캐시(cache-aside). 검색어·size 별로 엔진 결과를 담는다.
 *
 * <p>배포마다 JVM 이 바뀌는 blue/green 에서도 남아야 해서 앱 메모리가 아니라 Redis 에 둔다 — 운영은 한 달에 40번가량 배포돼 메모리
 * 캐시는 TTL(7일)을 채우지 못한다.
 *
 * <p>캐시는 없어도 되는 기능이다. Redis 가 느리거나 죽으면 경고만 남기고 DB 결과를 그대로 돌려준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreAutocompleteCache {

	static final String METRIC = "autocomplete.cache";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final StoreAutocompleteCacheProperties properties;
	private final MeterRegistry meterRegistry;

	private final Map<CacheResult, Counter> counters = new EnumMap<>(CacheResult.class);

	/** 캐시 조회 결과 — 지표 태그로 쓴다 */
	enum CacheResult {
		/** 캐시에 있었다 */
		HIT,
		/** 비어 있어 이 요청이 DB 를 조회해 채웠다 */
		MISS,
		/** 다른 요청이 채우는 중이라 기다렸다가 그 결과를 받았다(스탬피드를 막은 횟수) */
		COALESCED,
		/** Redis 오류로 캐시를 건너뛰고 DB 를 조회했다 */
		ERROR;

		String tagValue() {
			return name().toLowerCase();
		}
	}

	// 대시보드에서 "0건"과 "계측이 안 됨"을 구분하려고 태그 조합을 부팅 때 미리 등록한다(StoreSearchService 와 같은 이유)
	@PostConstruct
	void registerCounters() {
		for (CacheResult result : CacheResult.values()) {
			counter(result);
		}
	}

	/**
	 * @param normalizedQuery 정규화한 검색어(캐시 키)
	 * @param size            엔진에 요청한 후보 수(Hash field)
	 * @param loader          캐시가 비었을 때 DB 를 조회한다. 던진 예외는 캐시하지 않고 그대로 전파한다.
	 */
	public List<StoreAutocompleteSuggestion> getOrLoad(String normalizedQuery, int size,
			Supplier<List<StoreAutocompleteSuggestion>> loader) {
		if (!properties.isEnabled()) {
			return loader.get();
		}
		String key = AutocompleteCacheKeys.of(normalizedQuery);
		String field = AutocompleteCacheKeys.field(size);
		try {
			List<StoreAutocompleteSuggestion> cached = read(key, field);
			if (cached != null) {
				counter(CacheResult.HIT).increment();
				return cached;
			}
			if (!tryLock(normalizedQuery)) {
				List<StoreAutocompleteSuggestion> filledByOther = waitForFill(key, field);
				if (filledByOther != null) {
					counter(CacheResult.COALESCED).increment();
					return filledByOther;
				}
			}
		} catch (RuntimeException exception) {
			counter(CacheResult.ERROR).increment();
			log.warn("[자동완성 캐시] Redis 조회 실패 — DB 로 대신한다 query={} cause={}", normalizedQuery, exception.toString());
			return loader.get();
		}
		counter(CacheResult.MISS).increment();
		List<StoreAutocompleteSuggestion> loaded = loader.get();
		write(key, field, loaded);
		return loaded;
	}

	/** 같은 검색어를 동시에 채우려는 요청 중 하나만 DB 에 보낸다. 락은 TTL 로 풀린다 — 채우고 나면 다들 캐시를 읽으므로 지울 필요가 없다. */
	private boolean tryLock(String normalizedQuery) {
		return Boolean.TRUE.equals(redisTemplate.opsForValue()
			.setIfAbsent(AutocompleteCacheKeys.lockOf(normalizedQuery), "1", properties.getLockTtl()));
	}

	private List<StoreAutocompleteSuggestion> waitForFill(String key, String field) {
		for (int attempt = 0; attempt < properties.getLockRetries(); attempt++) {
			if (!pause(properties.getLockWait())) {
				return null;
			}
			List<StoreAutocompleteSuggestion> cached = read(key, field);
			if (cached != null) {
				return cached;
			}
		}
		return null;
	}

	private boolean pause(Duration wait) {
		try {
			Thread.sleep(wait.toMillis());
			return true;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** @return 캐시에 없거나 읽을 수 없는 값이면 null(다시 채운다) */
	private List<StoreAutocompleteSuggestion> read(String key, String field) {
		Object value = redisTemplate.opsForHash().get(key, field);
		if (!(value instanceof String json)) {
			return null;
		}
		try {
			return objectMapper.readValue(json,
				objectMapper.getTypeFactory().constructCollectionType(List.class, StoreAutocompleteSuggestion.class));
		} catch (JsonProcessingException exception) {
			// 응답 모양이 바뀐 뒤 남은 옛 값 — 미스로 보고 덮어쓴다
			log.info("[자동완성 캐시] 읽을 수 없는 값을 다시 채운다 key={}", key);
			return null;
		}
	}

	private void write(String key, String field, List<StoreAutocompleteSuggestion> suggestions) {
		try {
			redisTemplate.opsForHash().put(key, field, objectMapper.writeValueAsString(suggestions));
			redisTemplate.expire(key, jitteredTtl());
		} catch (JsonProcessingException | RuntimeException exception) {
			// 채우기 실패는 다음 요청이 다시 채운다 — 이미 조회한 결과는 그대로 돌려준다
			log.warn("[자동완성 캐시] 저장 실패 key={} cause={}", key, exception.toString());
		}
	}

	Duration jitteredTtl() {
		double ratio = properties.getTtlJitterRatio();
		if (ratio <= 0) {
			return properties.getTtl();
		}
		double factor = 1 + ThreadLocalRandom.current().nextDouble(-ratio, ratio);
		return Duration.ofSeconds(Math.max(1, Math.round(properties.getTtl().toSeconds() * factor)));
	}

	private Counter counter(CacheResult result) {
		return counters.computeIfAbsent(result, key -> Counter.builder(METRIC)
			.description("자동완성 Redis 캐시 조회 결과")
			.tag("result", key.tagValue())
			.register(meterRegistry));
	}
}
