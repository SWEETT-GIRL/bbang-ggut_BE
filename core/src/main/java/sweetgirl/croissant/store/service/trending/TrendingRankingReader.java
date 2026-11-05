package sweetgirl.croissant.store.service.trending;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreTrendingProperties;

/**
 * 자동완성이 요청마다 순위표를 본다. 순위표는 갱신 주기마다만 바뀌므로 Redis 를 요청마다 왕복하지 않고 짧게(30초) 메모리에 들고 있는다 —
 * 순위가 바뀐 뒤 최대 30초 늦게 반영될 뿐이다.
 *
 * <p>Redis 를 못 읽으면 직전 순위표를 계속 쓴다 — 페일오버 몇 초 때문에 급부상이 사라지지 않게. 마지막으로 읽은 지
 * {@code staleSnapshotLimit} 이 지났으면 빈 순위표로 본다. 그러면 자동완성은 급부상 없이 스테디 규칙으로만 정렬한다.
 * Redis 가 정상 응답한 빈 순위표는 "지금 뜨는 가게가 없다"는 뜻이므로 그대로 받아들인다.
 */
@Slf4j
@Component
public class TrendingRankingReader {

	static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);

	private final StringRedisTemplate redisTemplate;
	private final StoreTrendingProperties properties;
	private final ObjectMapper objectMapper;

	private final LongSupplier currentMillis;

	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

	/**
	 * @param checkedAtMillis 마지막으로 Redis 를 읽으려 한 시각(성공·실패 무관). 실패해도 TTL 동안은 다시 시도하지 않는다
	 * @param readAtMillis    마지막으로 읽기에 성공한 시각
	 */
	private record Snapshot(List<TrendingStore> stores, long checkedAtMillis, long readAtMillis) {
		static final Snapshot EMPTY = new Snapshot(List.of(), Long.MIN_VALUE / 2, Long.MIN_VALUE / 2);
	}

	@Autowired
	public TrendingRankingReader(StringRedisTemplate redisTemplate, StoreTrendingProperties properties,
			ObjectMapper objectMapper) {
		this(redisTemplate, properties, objectMapper, System::currentTimeMillis);
	}

	TrendingRankingReader(StringRedisTemplate redisTemplate, StoreTrendingProperties properties,
			ObjectMapper objectMapper, LongSupplier currentMillis) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.currentMillis = currentMillis;
	}

	/** @return 검색어로 시작하는 급부상 가게, 점수 높은 순으로 최대 limit 곳 */
	public List<TrendingStore> findStartingWith(String normalizedQuery, int limit) {
		if (!properties.isEnabled() || normalizedQuery.isBlank() || limit <= 0) {
			return List.of();
		}
		return current().stream()
			.filter(store -> store.startsWith(normalizedQuery))
			.limit(limit)
			.toList();
	}

	private List<TrendingStore> current() {
		Snapshot cached = snapshot.get();
		long now = currentMillis.getAsLong();
		if (now - cached.checkedAtMillis() < SNAPSHOT_TTL.toMillis()) {
			return cached.stores();
		}
		Snapshot next = refreshed(cached, now);
		snapshot.set(next);
		return next.stores();
	}

	private Snapshot refreshed(Snapshot cached, long now) {
		try {
			return new Snapshot(load(), now, now);
		} catch (JsonProcessingException | RuntimeException exception) {
			boolean usable = now - cached.readAtMillis() <= properties.getStaleSnapshotLimit().toMillis();
			log.warn("[급부상] 순위표를 못 읽어 {} cause={}", usable ? "직전 순위표를 쓴다" : "급부상 없이 정렬한다",
				exception.toString());
			return new Snapshot(usable ? cached.stores() : List.of(), now, cached.readAtMillis());
		}
	}

	private List<TrendingStore> load() throws JsonProcessingException {
		String json = redisTemplate.opsForValue().get(TrendingKeys.RANKING);
		if (json == null) {
			return List.of();
		}
		List<TrendingRankingEntry> entries = objectMapper.readValue(json,
			objectMapper.getTypeFactory().constructCollectionType(List.class, TrendingRankingEntry.class));
		return entries.stream()
			.map(entry -> new TrendingStore(entry.storeId(), entry.name(), entry.keys(), entry.score()))
			.toList();
	}
}
