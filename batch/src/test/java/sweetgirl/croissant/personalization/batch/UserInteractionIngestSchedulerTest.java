package sweetgirl.croissant.personalization.batch;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;
import sweetgirl.croissant.personalization.service.UserTasteProfileCommandService;

@ExtendWith(MockitoExtension.class)
class UserInteractionIngestSchedulerTest {

	@Mock
	private UserInteractionIngestCommandService ingestCommandService;
	@Mock
	private UserTasteProfileCommandService profileCommandService;
	@InjectMocks
	private UserInteractionIngestScheduler scheduler;

	@Test
	@DisplayName("04:00 — 적재 뒤 프로필 재계산 순서")
	void dailyIngest_ingestsThenRecomputes() {
		scheduler.dailyIngestAndRecompute();

		InOrder inOrder = Mockito.inOrder(ingestCommandService, profileCommandService);
		inOrder.verify(ingestCommandService).ingestYesterday();
		inOrder.verify(profileCommandService).recomputeAll();
	}

	@Test
	@DisplayName("적재가 실패해도 재계산은 돈다 — 어제까지의 신호로")
	void dailyIngest_recomputesEvenIfIngestFails() {
		given(ingestCommandService.ingestYesterday()).willThrow(new IllegalStateException("적재 실패"));

		scheduler.dailyIngestAndRecompute();

		then(profileCommandService).should().recomputeAll();
	}
}
