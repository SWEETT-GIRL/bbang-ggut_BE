package sweetgirl.croissant.store.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.service.trending.TrendingRankingService;

@ExtendWith(MockitoExtension.class)
class StoreTrendingSchedulerTest {

	@Mock
	private TrendingRankingService rankingService;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private StoreTrendingScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new StoreTrendingScheduler(rankingService, properties);
	}

	@Test
	@DisplayName("꺼져 있으면 순위표를 계산하지 않는다")
	void disabled_skipsRanking() {
		scheduler.refreshRanking();

		verifyNoInteractions(rankingService);
	}

	@Test
	@DisplayName("켜져 있으면 순위표를 계산한다")
	void enabled_refreshesRanking() {
		properties.setEnabled(true);

		scheduler.refreshRanking();

		verify(rankingService).refresh(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("실패해도 던지지 않는다 — 다음 주기에 다시 돈다")
	void failure_isSwallowed() {
		properties.setEnabled(true);
		given(rankingService.refresh(any(LocalDateTime.class))).willThrow(new RedisConnectionFailureException("down"));

		scheduler.refreshRanking();
	}
}
