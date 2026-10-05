package sweetgirl.croissant.store.service.trending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.store.config.StoreTrendingProperties;

@ExtendWith(MockitoExtension.class)
class TrendingRankingReaderTest {

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ZSetOperations<String, String> zSetOperations;
	@Mock
	private HashOperations<String, Object, Object> hashOperations;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private TrendingRankingReader reader;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		reader = new TrendingRankingReader(redisTemplate, properties, new ObjectMapper());
	}

	private void givenRanking() {
		Set<TypedTuple<String>> top = new LinkedHashSet<>(List.of(
			new DefaultTypedTuple<>("2", 9.0), new DefaultTypedTuple<>("7", 5.0), new DefaultTypedTuple<>("3", 4.0)));
		given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
		given(zSetOperations.reverseRangeWithScores("trend:top", 0, -1)).willReturn(top);
		given(redisTemplate.<Object, Object>opsForHash()).willReturn(hashOperations);
		given(hashOperations.entries("trend:names")).willReturn(Map.of(
			"2", "{\"name\":\"한정선\",\"keys\":[\"한정선\"]}",
			"7", "{\"name\":\"성심당 본점\",\"keys\":[\"성심당본점\",\"성심\"]}",
			"3", "{\"name\":\"한스베이커리\",\"keys\":[\"한스베이커리\"]}"));
	}

	@Test
	@DisplayName("검색어로 시작하는 가게만 점수 높은 순으로 상한까지 준다")
	void findStartingWith_filtersByPrefixAndLimits() {
		givenRanking();

		assertThat(reader.findStartingWith("한", 1)).extracting(TrendingStore::name).containsExactly("한정선");
		assertThat(reader.findStartingWith("한", 2)).extracting(TrendingStore::name)
			.containsExactly("한정선", "한스베이커리");
	}

	@Test
	@DisplayName("별칭으로 시작해도 잡는다")
	void findStartingWith_matchesAliases() {
		givenRanking();

		assertThat(reader.findStartingWith("성심", 2)).extracting(TrendingStore::storeId).containsExactly(7L);
	}

	@Test
	@DisplayName("순위표는 30초 동안 메모리에 들고 있어 요청마다 Redis 를 왕복하지 않는다")
	void snapshot_isReusedWithinTtl() {
		givenRanking();

		reader.findStartingWith("한", 2);
		reader.findStartingWith("성", 2);

		verify(zSetOperations, times(1)).reverseRangeWithScores("trend:top", 0, -1);
	}

	@Test
	@DisplayName("Redis 를 못 읽으면 빈 순위표로 본다 — 자동완성은 급부상 없이 정렬된다")
	void redisFailure_returnsEmpty() {
		given(redisTemplate.opsForZSet()).willThrow(new RedisConnectionFailureException("down"));

		assertThat(reader.findStartingWith("한", 2)).isEmpty();
	}

	@Test
	@DisplayName("꺼져 있으면 Redis 를 건드리지 않는다")
	void disabled_returnsEmpty() {
		properties.setEnabled(false);

		assertThat(reader.findStartingWith("한", 2)).isEmpty();
		verifyNoInteractions(redisTemplate);
	}
}
