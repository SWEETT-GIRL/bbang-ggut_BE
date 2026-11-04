package sweetgirl.croissant.store.service.trending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.store.config.StoreTrendingProperties;

@ExtendWith(MockitoExtension.class)
class TrendingRankingReaderTest {

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOperations;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private final AtomicLong clock = new AtomicLong(1_000_000L);
	private TrendingRankingReader reader;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		reader = new TrendingRankingReader(redisTemplate, properties, new ObjectMapper(), clock::get);
	}

	private void givenRanking() {
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("trend:ranking")).willReturn("""
				[{"storeId":2,"score":9.0,"name":"한정선","keys":["한정선"]},
				{"storeId":7,"score":5.0,"name":"성심당 본점","keys":["성심당본점","성심"]},
				{"storeId":3,"score":4.0,"name":"한스베이커리","keys":["한스베이커리"]}]
				""");
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

		verify(valueOperations, times(1)).get("trend:ranking");
	}

	private void expireSnapshot() {
		clock.addAndGet(TrendingRankingReader.SNAPSHOT_TTL.toMillis());
	}

	@Test
	@DisplayName("Redis 를 못 읽으면 직전 순위표를 계속 쓴다 — 페일오버 동안 급부상이 사라지지 않는다")
	void redisFailure_keepsLastSnapshot() {
		givenRanking();
		reader.findStartingWith("한", 2);
		expireSnapshot();
		given(redisTemplate.opsForValue()).willThrow(new RedisConnectionFailureException("down"));

		assertThat(reader.findStartingWith("한", 1)).extracting(TrendingStore::name).containsExactly("한정선");
	}

	@Test
	@DisplayName("장애 중에도 TTL 동안은 다시 시도하지 않는다 — 요청마다 Redis 타임아웃을 기다리지 않게")
	void redisFailure_doesNotRetryWithinTtl() {
		given(redisTemplate.opsForValue()).willThrow(new RedisConnectionFailureException("down"));

		reader.findStartingWith("한", 2);
		reader.findStartingWith("성", 2);

		verify(redisTemplate, times(1)).opsForValue();
	}

	@Test
	@DisplayName("마지막으로 읽은 지 보관 한도를 넘기면 직전 순위표를 버린다")
	void redisFailure_dropsSnapshotAfterStaleLimit() {
		givenRanking();
		reader.findStartingWith("한", 2);
		clock.addAndGet(properties.getStaleSnapshotLimit().toMillis() + 1);
		given(redisTemplate.opsForValue()).willThrow(new RedisConnectionFailureException("down"));

		assertThat(reader.findStartingWith("한", 2)).isEmpty();
	}

	@Test
	@DisplayName("Redis 가 복구되면 다음 갱신 때 새 순위표로 돌아온다")
	void redisRecovery_replacesSnapshot() {
		givenRanking();
		reader.findStartingWith("한", 2);
		expireSnapshot();
		reset(redisTemplate);
		given(redisTemplate.opsForValue()).willThrow(new RedisConnectionFailureException("down"));
		reader.findStartingWith("한", 2);
		expireSnapshot();
		reset(redisTemplate, valueOperations);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("trend:ranking")).willReturn("""
				[{"storeId":3,"score":4.0,"name":"한스베이커리","keys":["한스베이커리"]}]
				""");

		assertThat(reader.findStartingWith("한", 2)).extracting(TrendingStore::name).containsExactly("한스베이커리");
	}

	@Test
	@DisplayName("순위표 키가 없으면 그대로 받아들인다 — 지금 뜨는 가게가 없거나 갱신이 오래 멈춰 만료됐다는 뜻이다")
	void emptyRanking_replacesSnapshot() {
		givenRanking();
		reader.findStartingWith("한", 2);
		expireSnapshot();
		given(valueOperations.get("trend:ranking")).willReturn(null);

		assertThat(reader.findStartingWith("한", 2)).isEmpty();
	}

	@Test
	@DisplayName("읽은 적 없이 Redis 를 못 읽으면 빈 순위표로 본다 — 자동완성은 급부상 없이 정렬된다")
	void redisFailure_returnsEmpty() {
		given(redisTemplate.opsForValue()).willThrow(new RedisConnectionFailureException("down"));

		assertThat(reader.findStartingWith("한", 2)).isEmpty();
	}

	@Test
	@DisplayName("꺼져 있으면 Redis 를 건드리지 않는다")
	void disabled_returnsEmpty() {
		properties.setEnabled(false);

		assertThat(reader.findStartingWith("한", 2)).isEmpty();
		verifyNoInteractions(redisTemplate);
	}

	@Test
	@DisplayName("순위표 값을 해석하지 못하면 읽기 실패로 보고 직전 순위표를 쓴다")
	void malformedRanking_keepsLastSnapshot() {
		givenRanking();
		reader.findStartingWith("한", 2);
		expireSnapshot();
		given(valueOperations.get("trend:ranking")).willReturn("not-json");

		assertThat(reader.findStartingWith("한", 1)).extracting(TrendingStore::name).containsExactly("한정선");
	}
}
