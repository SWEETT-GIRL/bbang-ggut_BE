package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;
import sweetgirl.croissant.menu.config.MenuClassificationProperties;
import sweetgirl.croissant.menu.dto.classification.MenuClassificationBatchResult;

@ExtendWith(MockitoExtension.class)
class MenuClassificationSchedulerTest {

	@Mock
	private MenuClassificationService menuClassificationService;

	private MenuClassificationProperties properties;
	private MenuClassificationScheduler scheduler;

	@BeforeEach
	void setUp() {
		properties = new MenuClassificationProperties();
		properties.setRateLimitBackoff(Duration.ofHours(1));
		scheduler = new MenuClassificationScheduler(menuClassificationService, properties);
	}

	@Test
	@DisplayName("꺼져 있으면 배치를 잡지 않는다")
	void skipsWhenDisabled() {
		properties.setEnabled(false);

		scheduler.classifyNextBatch();

		verify(menuClassificationService, never()).classifyNextBatch();
	}

	@Test
	@DisplayName("한도(429)를 끝까지 맞으면 백오프 동안 배치를 건너뛴다")
	void pausesAfterRateLimited() {
		willThrow(new GeminiException(GeminiErrorCode.RATE_LIMITED))
			.given(menuClassificationService).classifyNextBatch();

		scheduler.classifyNextBatch();
		scheduler.classifyNextBatch();
		scheduler.classifyNextBatch();

		// 첫 번째만 실제로 부르고 나머지는 쉬는 중이라 건너뛴다 —
		// 이걸 안 하면 진도는 안 나가면서 남은 할당량만 태운다
		verify(menuClassificationService, times(1)).classifyNextBatch();
		Instant resumeAt = (Instant)ReflectionTestUtils.getField(scheduler, "resumeAt");
		assertThat(resumeAt).isAfter(Instant.now().plus(Duration.ofMinutes(50)));
	}

	@Test
	@DisplayName("쉬는 시간이 지나면 알아서 다시 돈다 — 사람이 설정을 고칠 일이 없다")
	void resumesAfterBackoff() {
		given(menuClassificationService.classifyNextBatch()).willReturn(MenuClassificationBatchResult.empty());
		ReflectionTestUtils.setField(scheduler, "resumeAt", Instant.now().minusSeconds(1));

		scheduler.classifyNextBatch();

		verify(menuClassificationService).classifyNextBatch();
	}

	@Test
	@DisplayName("한도가 아닌 실패는 쉬지 않는다 — 다음 주기에 같은 행부터 다시")
	void doesNotPauseOnOtherFailures() {
		willThrow(new GeminiException(GeminiErrorCode.UNAVAILABLE))
			.given(menuClassificationService).classifyNextBatch();

		scheduler.classifyNextBatch();
		scheduler.classifyNextBatch();

		verify(menuClassificationService, times(2)).classifyNextBatch();
	}

	@Test
	@DisplayName("배치가 예외를 던져도 스케줄러는 죽지 않는다")
	void survivesRuntimeException() {
		willThrow(new IllegalStateException("DB 끊김"))
			.given(menuClassificationService).classifyNextBatch();

		scheduler.classifyNextBatch();

		verify(menuClassificationService).classifyNextBatch();
	}
}
