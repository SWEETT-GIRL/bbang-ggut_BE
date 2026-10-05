package sweetgirl.croissant.store.service.trending;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreTrendingProperties;

/**
 * 자동완성이 요청마다 순위표를 본다. 순위표는 5분마다만 바뀌므로 Redis 를 요청마다 왕복하지 않고 짧게(30초) 메모리에 들고 있는다 —
 * 순위가 바뀐 뒤 최대 30초 늦게 반영될 뿐이다.
 *
 * <p>Redis 를 못 읽으면 빈 순위표로 본다. 그러면 자동완성은 급부상 없이 스테디 규칙으로만 정렬한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingRankingReader {

	static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);

	private final StringRedisTemplate redisTemplate;
	private final StoreTrendingProperties properties;
	private final ObjectMapper objectMapper;

	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

	private record Snapshot(List<TrendingStore> stores, long loadedAtMillis) {
		static final Snapshot EMPTY = new Snapshot(List.of(), 0);
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
		if (System.currentTimeMillis() - cached.loadedAtMillis() < SNAPSHOT_TTL.toMillis()) {
			return cached.stores();
		}
		Snapshot loaded = new Snapshot(load(), System.currentTimeMillis());
		snapshot.set(loaded);
		return loaded.stores();
	}

	private List<TrendingStore> load() {
		try {
			Set<TypedTuple<String>> top = redisTemplate.opsForZSet().reverseRangeWithScores(TrendingKeys.TOP, 0, -1);
			if (top == null || top.isEmpty()) {
				return List.of();
			}
			Map<Object, Object> names = redisTemplate.opsForHash().entries(TrendingKeys.NAMES);
			List<TrendingStore> stores = new ArrayList<>();
			for (TypedTuple<String> tuple : top) {
				Object json = names.get(tuple.getValue());
				if (tuple.getValue() == null || tuple.getScore() == null || !(json instanceof String text)) {
					continue;
				}
				TrendingRankingService.TrendingNameEntry entry = objectMapper.readValue(text,
					TrendingRankingService.TrendingNameEntry.class);
				stores.add(new TrendingStore(Long.parseLong(tuple.getValue()), entry.name(), entry.keys(),
					tuple.getScore()));
			}
			stores.sort(Comparator.comparingDouble(TrendingStore::score).reversed());
			return List.copyOf(stores);
		} catch (JsonProcessingException | RuntimeException exception) {
			log.warn("[급부상] 순위표를 못 읽어 급부상 없이 정렬한다 cause={}", exception.toString());
			return List.of();
		}
	}
}
