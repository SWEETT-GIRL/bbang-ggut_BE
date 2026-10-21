package sweetgirl.croissant.store.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.service.StoreTrendingBaselineCommandService;

@ExtendWith(MockitoExtension.class)
class StoreTrendingBaselineSchedulerTest {

	@Mock
	private StoreTrendingBaselineCommandService baselineCommandService;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private StoreTrendingBaselineScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new StoreTrendingBaselineScheduler(baselineCommandService, properties);
	}

	@Test
	@DisplayName("꺼져 있으면 평소 수준을 계산하지 않는다")
	void disabled_skipsBaseline() {
		scheduler.dailyBaselineRefresh();

		verifyNoInteractions(baselineCommandService);
	}

	@Test
	@DisplayName("켜져 있으면 오늘 기준으로 평소 수준을 계산한다")
	void enabled_refreshesBaseline() {
		properties.setEnabled(true);

		scheduler.dailyBaselineRefresh();

		verify(baselineCommandService).refreshBaselines(any(LocalDate.class));
	}

	@Test
	@DisplayName("실패해도 던지지 않는다 — 다음 날 다시 돈다")
	void failure_isSwallowed() {
		properties.setEnabled(true);
		given(baselineCommandService.refreshBaselines(any(LocalDate.class))).willThrow(new IllegalStateException("db"));

		scheduler.dailyBaselineRefresh();
	}
}
