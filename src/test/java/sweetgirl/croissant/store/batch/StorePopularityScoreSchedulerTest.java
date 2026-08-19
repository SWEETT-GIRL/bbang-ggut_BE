package sweetgirl.croissant.store.batch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.service.StorePopularityCommandService;

@ExtendWith(MockitoExtension.class)
class StorePopularityScoreSchedulerTest {

	@Mock
	private StorePopularityCommandService storePopularityCommandService;

	@InjectMocks
	private StorePopularityScoreScheduler scheduler;

	@Test
	void weeklyPopularityRefresh_delegatesToService() {
		given(storePopularityCommandService.refreshAllPopularityScores()).willReturn(10);

		scheduler.weeklyPopularityRefresh();

		verify(storePopularityCommandService).refreshAllPopularityScores();
	}

	@Test
	void weeklyPopularityRefresh_swallowsFailureSoSchedulerKeepsRunning() {
		given(storePopularityCommandService.refreshAllPopularityScores()).willThrow(new IllegalStateException("boom"));

		assertThatCode(() -> scheduler.weeklyPopularityRefresh()).doesNotThrowAnyException();
	}
}
