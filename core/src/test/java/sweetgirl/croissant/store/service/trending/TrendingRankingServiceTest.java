package sweetgirl.croissant.store.service.trending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.repository.trending.StoreTrendingRepository;
import sweetgirl.croissant.store.repository.trending.TrendingStoreName;

@ExtendWith(MockitoExtension.class)
class TrendingRankingServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 15, 0);

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOperations;
	@Mock
	private ZSetOperations<String, String> zSetOperations;
	@Mock
	private HyperLogLogOperations<String, String> hyperLogLogOperations;
	@Mock
	private StoreTrendingRepository repository;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private TrendingRankingService service;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		service = new TrendingRankingService(redisTemplate, repository, properties, new ObjectMapper());

		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).willReturn(true);
		given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
		Set<TypedTuple<String>> recent = Set.of(new DefaultTypedTuple<>("900", 20.0));
		given(zSetOperations.rangeWithScores("trend:recent:work", 0, -1)).willReturn(recent);
		given(redisTemplate.opsForHyperLogLog()).willReturn(hyperLogLogOperations);
		given(hyperLogLogOperations.size(any(String[].class))).willReturn(5L);
		given(repository.findBaselines(anyCollection())).willReturn(Map.of(900L, 0.0));
	}

	@Test
	@DisplayName("순위표를 키 하나에 TTL 과 함께 쓴다 — 갱신이 멈추면 옛 순위가 끝없이 승격되지 않게")
	void refresh_writesRankingWithTtl() {
		given(repository.findActiveNames(anyCollection())).willReturn(
			List.of(new TrendingStoreName(900L, "폼포미베이커리", List.of("폼포미베이커리"))));

		assertThat(service.refresh(NOW)).isEqualTo(1);

		verify(zSetOperations).unionAndStore(anyString(), anyList(), eq("trend:recent:work"), eq(Aggregate.SUM),
			any(Weights.class));
		verify(valueOperations).set(eq("trend:ranking"), contains("폼포미베이커리"),
			eq(Duration.ofMinutes(90)));
		verify(redisTemplate).delete("lock:trend:refresh");
	}

	@Test
	@DisplayName("자격을 통과한 가게가 전부 숨김이면 순위표를 지운다")
	void refresh_clearsRankingWhenAllHidden() {
		given(repository.findActiveNames(anyCollection())).willReturn(List.of());

		assertThat(service.refresh(NOW)).isZero();

		verify(redisTemplate).delete("trend:ranking");
		verify(redisTemplate).delete("lock:trend:refresh");
	}
}
