package sweetgirl.croissant.store.service.trending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.repository.trending.StoreTrendingRepository;
import sweetgirl.croissant.store.repository.trending.TrendingStoreName;

/**
 * 신호 기록 → 순위표 계산 → 자동완성 읽기를 진짜 Redis 로 끝까지 돈다. ZUNIONSTORE 가중치·PFCOUNT 합치기·순위표 직렬화는 목으로 못 본다.
 * 로컬 Redis(localhost:6379)가 떠 있을 때 {@code ./gradlew test -PlabTests} 로 돈다. DB 는 목이다(평소 수준·이름).
 */
@Tag("lab")
class TrendingRankingRedisLabTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 15, 0);

	private LettuceConnectionFactory connectionFactory;
	private StringRedisTemplate redisTemplate;
	private TrendingSignalRecorder recorder;
	private TrendingRankingService rankingService;
	private TrendingRankingReader reader;
	private final StoreTrendingRepository repository = mock(StoreTrendingRepository.class);

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

		StoreTrendingProperties properties = new StoreTrendingProperties();
		properties.setEnabled(true);
		ObjectMapper objectMapper = new ObjectMapper();
		recorder = new TrendingSignalRecorder(redisTemplate, properties);
		rankingService = new TrendingRankingService(redisTemplate, repository, properties, objectMapper);
		reader = new TrendingRankingReader(redisTemplate, properties, objectMapper);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("오늘 여러 회원이 몰린 새 가게는 순위표에 오르고, 평소대로인 유명 가게와 한 명만 본 가게는 오르지 않는다")
	void surgingStoreIsPublishedAndReadable() {
		// 새 가게(평소 0): 회원 5명이 조회, 그중 3명은 찜까지
		for (long member = 1; member <= 5; member++) {
			recorder.record(member, 900L, TrendingSignal.VIEW, NOW.minusHours(member));
		}
		for (long member = 1; member <= 3; member++) {
			recorder.record(member, 900L, TrendingSignal.SAVE, NOW.minusHours(member));
			recorder.record(member, 900L, TrendingSignal.SAVE, NOW.minusHours(member)); // 같은 날 또 — 한 번만
		}
		// 유명 가게(평소 하루 2): 오늘 회원 4명 조회 — 평소 수준이라 배율이 낮다
		for (long member = 11; member <= 14; member++) {
			recorder.record(member, 75L, TrendingSignal.VIEW, NOW.minusDays(member - 11));
		}
		// 한 명이 여러 행동 — 최소 회원 수 미달
		recorder.record(21L, 500L, TrendingSignal.RECORD, NOW);
		recorder.record(21L, 500L, TrendingSignal.SAVE, NOW);

		given(repository.findBaselines(anyCollection())).willReturn(Map.of(900L, 0.0, 75L, 2.0, 500L, 0.0));
		given(repository.findActiveNames(anyCollection())).willReturn(List.of(
			new TrendingStoreName(900L, "폼포미베이커리", List.of("폼포미베이커리"))));

		int published = rankingService.refresh(NOW);

		assertThat(published).isEqualTo(1);
		assertThat(redisTemplate.opsForValue().get("trend:ranking")).contains("폼포미베이커리");
		assertThat(redisTemplate.getExpire("trend:ranking")).isPositive();
		assertThat(reader.findStartingWith("폼", 2)).extracting(TrendingStore::name).containsExactly("폼포미베이커리");
		assertThat(redisTemplate.hasKey("lock:trend:refresh")).isFalse();
	}

	@Test
	@DisplayName("뜨는 가게가 없으면 순위표를 비운다 — 지난 순위가 남아 계속 승격되지 않게")
	void emptyRankingClearsPreviousTop() {
		redisTemplate.opsForValue().set("trend:ranking",
			"[{\"storeId\":1,\"score\":3.0,\"name\":\"옛\",\"keys\":[\"옛\"]}]");

		int published = rankingService.refresh(NOW);

		assertThat(published).isZero();
		assertThat(redisTemplate.hasKey("trend:ranking")).isFalse();
	}

	@Test
	@DisplayName("신호 하나를 기록하면 판정·버킷·회원 키가 모두 생기고 전부 TTL 이 걸린다")
	void recordScript_writesAllKeysWithTtl() {
		recorder.record(7L, 10L, TrendingSignal.SAVE, NOW);
		recorder.record(7L, 10L, TrendingSignal.SAVE, NOW);

		assertThat(redisTemplate.opsForZSet().score("trend:h:2026100515", "10")).isEqualTo(2.0);
		assertThat(redisTemplate.opsForHyperLogLog().size("trend:uv:10:20261005")).isEqualTo(1);
		assertThat(redisTemplate.getExpire("seen:SAVE:10:7:20261005")).isPositive();
		assertThat(redisTemplate.getExpire("trend:h:2026100515")).isPositive();
		assertThat(redisTemplate.getExpire("trend:uv:10:20261005")).isPositive();
	}

	@Test
	@DisplayName("스크립트 캐시가 빈 Redis(갓 승격한 노드)에서도 기록된다 — NOSCRIPT 로 신호를 잃지 않는다")
	void recordScript_survivesEmptyScriptCache() {
		recorder.record(7L, 10L, TrendingSignal.SAVE, NOW);
		redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>)connection -> {
			connection.scriptingCommands().scriptFlush();
			return null;
		});

		recorder.record(8L, 10L, TrendingSignal.SAVE, NOW);

		assertThat(redisTemplate.opsForZSet().score("trend:h:2026100515", "10")).isEqualTo(4.0);
	}

	@Test
	@DisplayName("다른 인스턴스가 계산 중이면 건너뛴다(blue/green 전환 중 중복 계산 방지)")
	void skipsWhenAnotherInstanceHoldsLock() {
		redisTemplate.opsForValue().set("lock:trend:refresh", "1");

		assertThat(rankingService.refresh(NOW)).isEqualTo(-1);
	}
}
