package sweetgirl.croissant.store.service.trending;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.repository.trending.StoreTrendingRepository;
import sweetgirl.croissant.store.repository.trending.TrendingStoreName;

/**
 * 급부상 순위표(trend:top · trend:names)를 다시 만든다. 5분마다 스케줄러가 부른다.
 *
 * <ol>
 * <li>최근 창(7일)의 시간 버킷을 감쇠 가중치로 합친다(ZUNIONSTORE) → 가게별 recent</li>
 * <li>가게별 7일 고유 회원 수(PFCOUNT 로 7일치 HLL 을 합쳐 셈), 평소 수준(DB)을 붙인다</li>
 * <li>자격·순위를 계산하고 이름·별칭을 붙여 임시 키에 쓴 뒤 RENAME — 읽는 쪽이 반쯤 찬 순위표를 보지 않는다</li>
 * </ol>
 * blue/green 전환 중 두 JVM 이 함께 돌아도 한쪽만 계산하도록 Redis 락을 건다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendingRankingService {

	/** 주기(5분)보다 짧게 — 계산하던 인스턴스가 죽어도 다음 주기에는 풀린다 */
	static final Duration LOCK_TTL = Duration.ofMinutes(4);
	private static final String RECENT_UNION = "trend:recent:work";

	private final StringRedisTemplate redisTemplate;
	private final StoreTrendingRepository storeTrendingRepository;
	private final StoreTrendingProperties properties;
	private final ObjectMapper objectMapper;

	/** @return 순위표에 오른 가게 수(다른 인스턴스가 계산 중이면 -1) */
	public int refresh(LocalDateTime now) {
		if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(TrendingKeys.REFRESH_LOCK, "1", LOCK_TTL))) {
			return -1;
		}
		try {
			Map<Long, Double> recent = sumRecentBuckets(now);
			if (recent.isEmpty()) {
				redisTemplate.delete(List.of(TrendingKeys.TOP, TrendingKeys.NAMES));
				return 0;
			}
			Map<Long, Double> baselines = storeTrendingRepository.findBaselines(recent.keySet());
			List<TrendingRankingCalculator.Candidate> candidates = recent.entrySet().stream()
				.map(entry -> new TrendingRankingCalculator.Candidate(entry.getKey(), entry.getValue(),
					uniqueMembers(entry.getKey(), now), baselines.getOrDefault(entry.getKey(), 0.0)))
				.toList();
			List<TrendingRankingCalculator.Ranked> ranked = TrendingRankingCalculator.rank(candidates, properties);
			int published = publish(ranked);
			log.info("[급부상] 순위표 갱신 candidates={} published={}", candidates.size(), published);
			return published;
		} finally {
			redisTemplate.delete(TrendingKeys.REFRESH_LOCK);
		}
	}

	private Map<Long, Double> sumRecentBuckets(LocalDateTime now) {
		long windowHours = properties.getWindow().toHours();
		List<String> keys = new ArrayList<>();
		double[] weights = new double[(int)windowHours];
		for (int age = 0; age < windowHours; age++) {
			keys.add(TrendingKeys.hourBucket(now.minusHours(age)));
			weights[age] = TrendingRankingCalculator.decayWeight(age, properties.getHalfLife());
		}
		redisTemplate.opsForZSet().unionAndStore(keys.get(0), keys.subList(1, keys.size()), RECENT_UNION,
			Aggregate.SUM, Weights.of(weights));
		Set<TypedTuple<String>> tuples = redisTemplate.opsForZSet().rangeWithScores(RECENT_UNION, 0, -1);
		redisTemplate.delete(RECENT_UNION);
		Map<Long, Double> recent = new HashMap<>();
		if (tuples != null) {
			for (TypedTuple<String> tuple : tuples) {
				if (tuple.getValue() != null && tuple.getScore() != null) {
					recent.put(Long.valueOf(tuple.getValue()), tuple.getScore());
				}
			}
		}
		return recent;
	}

	private long uniqueMembers(long storeId, LocalDateTime now) {
		String[] keys = new String[(int)properties.getWindow().toDays()];
		for (int age = 0; age < keys.length; age++) {
			keys[age] = TrendingKeys.uniqueMembers(storeId, now.toLocalDate().minusDays(age));
		}
		Long count = redisTemplate.opsForHyperLogLog().size(keys);
		return count != null ? count : 0;
	}

	private int publish(List<TrendingRankingCalculator.Ranked> ranked) {
		Map<Long, TrendingStoreName> names = storeTrendingRepository.findActiveNames(
			ranked.stream().map(TrendingRankingCalculator.Ranked::storeId)
				.collect(Collectors.toCollection(HashSet::new)))
			.stream().collect(Collectors.toMap(TrendingStoreName::storeId, Function.identity()));
		String topWork = TrendingKeys.staging(TrendingKeys.TOP);
		String namesWork = TrendingKeys.staging(TrendingKeys.NAMES);
		redisTemplate.delete(List.of(topWork, namesWork));
		Map<String, String> nameEntries = new LinkedHashMap<>();
		for (TrendingRankingCalculator.Ranked store : ranked) {
			TrendingStoreName name = names.get(store.storeId());
			if (name == null) {
				continue; // 숨긴 가게는 올리지 않는다
			}
			redisTemplate.opsForZSet().add(topWork, String.valueOf(store.storeId()), store.score());
			nameEntries.put(String.valueOf(store.storeId()), toJson(name));
		}
		if (nameEntries.isEmpty()) {
			redisTemplate.delete(List.of(TrendingKeys.TOP, TrendingKeys.NAMES));
			return 0;
		}
		redisTemplate.opsForHash().putAll(namesWork, nameEntries);
		redisTemplate.rename(topWork, TrendingKeys.TOP);
		redisTemplate.rename(namesWork, TrendingKeys.NAMES);
		return nameEntries.size();
	}

	private String toJson(TrendingStoreName name) {
		try {
			return objectMapper.writeValueAsString(new TrendingNameEntry(name.name(), name.normalizedKeys()));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("급부상 이름을 JSON 으로 못 바꿨다 store=" + name.storeId(), exception);
		}
	}

	/** trend:names 의 값 */
	record TrendingNameEntry(String name, List<String> keys) {
	}
}
