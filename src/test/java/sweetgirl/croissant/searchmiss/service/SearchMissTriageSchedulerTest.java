package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.exception.NaverSearchErrorCode;
import sweetgirl.croissant.external.naver.exception.NaverSearchException;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageSchedulerTest {

	@Mock
	private SearchMissTriageService triageService;

	private SearchMissTriageProperties properties;
	private SearchMissTriageScheduler scheduler;

	@BeforeEach
	void setUp() {
		properties = new SearchMissTriageProperties();
		properties.setEnabled(true);
		properties.setRateLimitBackoff(Duration.ofHours(1));
		scheduler = new SearchMissTriageScheduler(triageService, properties);
	}

	@Test
	@DisplayName("꺼져 있으면 배치를 잡지 않는다 — 기본값이 꺼짐이다")
	void skipsWhenDisabled() {
		SearchMissTriageScheduler defaultScheduler = new SearchMissTriageScheduler(triageService,
			new SearchMissTriageProperties());

		defaultScheduler.triageNextBatch();

		verify(triageService, never()).triageNextBatch();
	}

	@Test
	@DisplayName("켜져 있으면 배치를 돌린다")
	void runsWhenEnabled() {
		given(triageService.triageNextBatch()).willReturn(new SearchMissTriageBatchResult(0, Map.of(), 0));

		scheduler.triageNextBatch();

		verify(triageService).triageNextBatch();
	}

	@Test
	@DisplayName("네이버 한도에 걸리면 백오프 동안 배치를 건너뛴다")
	void pausesAfterNaverRateLimited() {
		willThrow(new NaverSearchException(NaverSearchErrorCode.RATE_LIMITED)).given(triageService).triageNextBatch();

		scheduler.triageNextBatch();
		scheduler.triageNextBatch();

		verify(triageService, times(1)).triageNextBatch();
		Instant resumeAt = (Instant)ReflectionTestUtils.getField(scheduler, "resumeAt");
		assertThat(resumeAt).isAfter(Instant.now().plus(Duration.ofMinutes(50)));
	}

	@Test
	@DisplayName("LLM 한도에 걸려도 쉰다")
	void pausesAfterLlmRateLimited() {
		willThrow(new LlmException(LlmErrorCode.RATE_LIMITED)).given(triageService).triageNextBatch();

		scheduler.triageNextBatch();
		scheduler.triageNextBatch();

		verify(triageService, times(1)).triageNextBatch();
	}

	@Test
	@DisplayName("그 밖의 실패는 스케줄러를 멈추지 않고 다음 주기에 다시 돈다")
	void continuesAfterOtherFailure() {
		willThrow(new IllegalStateException("DB 끊김")).given(triageService).triageNextBatch();

		scheduler.triageNextBatch();
		scheduler.triageNextBatch();

		verify(triageService, times(2)).triageNextBatch();
	}
}
