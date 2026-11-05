package sweetgirl.croissant.alarm.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.service.PushBatchService;

@ExtendWith(MockitoExtension.class)
class PushBatchSchedulerTest {

	@Mock
	private PushBatchService pushBatchService;

	@InjectMocks
	private PushBatchScheduler pushBatchScheduler;

	@Test
	@DisplayName("매분 창이 지난 묶음을 내보낸다")
	void flushes() {
		pushBatchScheduler.flushDue();

		then(pushBatchService).should().flushDue(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("내보내다 터져도 스케줄러는 죽지 않는다")
	void survivesFailure() {
		given(pushBatchService.flushDue(any(LocalDateTime.class))).willThrow(new IllegalStateException("db"));

		pushBatchScheduler.flushDue();

		then(pushBatchService).should().flushDue(any(LocalDateTime.class));
	}
}
