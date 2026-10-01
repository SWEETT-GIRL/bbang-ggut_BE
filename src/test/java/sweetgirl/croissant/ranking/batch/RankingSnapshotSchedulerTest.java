package sweetgirl.croissant.ranking.batch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.ranking.service.RankingSnapshotCommandService;

@ExtendWith(MockitoExtension.class)
class RankingSnapshotSchedulerTest {

	@Mock
	private RankingSnapshotCommandService snapshotCommandService;

	@InjectMocks
	private RankingSnapshotScheduler scheduler;

	@Test
	void dailySnapshot_delegatesToService() {
		given(snapshotCommandService.snapshotYesterday()).willReturn(300);

		scheduler.dailySnapshot();

		verify(snapshotCommandService).snapshotYesterday();
	}

	@Test
	void dailySnapshot_swallowsFailureSoSchedulerKeepsRunning() {
		given(snapshotCommandService.snapshotYesterday()).willThrow(new IllegalStateException("boom"));

		assertThatCode(() -> scheduler.dailySnapshot()).doesNotThrowAnyException();
	}
}
