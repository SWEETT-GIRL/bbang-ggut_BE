package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.exception.NaverSearchErrorCode;
import sweetgirl.croissant.external.naver.exception.NaverSearchException;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageSchedulerTest {

	@Mock
	private SearchMissTriageService triageService;

	private SearchMissTriageScheduler scheduler;

	@BeforeEach
	void setUp() {
		SearchMissTriageProperties properties = new SearchMissTriageProperties();
		properties.setEnabled(true);
		scheduler = new SearchMissTriageScheduler(triageService, properties);
	}

	@Test
	@DisplayName("꺼져 있으면 배치를 잡지 않는다 — 기본값이 꺼짐이다")
	void skipsWhenDisabled() {
		SearchMissTriageScheduler defaultScheduler = new SearchMissTriageScheduler(triageService,
			new SearchMissTriageProperties());

		defaultScheduler.triageDaily();

		verify(triageService, never()).triageNextBatch();
	}

	@Test
	@DisplayName("켜져 있으면 배치를 돌린다")
	void runsWhenEnabled() {
		given(triageService.triageNextBatch()).willReturn(new SearchMissTriageBatchResult(0, Map.of(), 0));

		scheduler.triageDaily();

		verify(triageService).triageNextBatch();
	}

	@Test
	@DisplayName("하루 한 번 새벽 6시(KST)에 돈다 — 설정으로 바꿀 수 있다")
	void runsDailyAtSixInSeoul() throws NoSuchMethodException {
		Method method = SearchMissTriageScheduler.class.getMethod("triageDaily");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled.cron()).isEqualTo("${search-miss.triage.cron:0 0 6 * * *}");
		assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("네이버·LLM 한도에 걸려도 예외를 밖으로 내지 않고, 다음 회차는 그대로 돈다")
	void swallowsRateLimitAndRunsNextTime() {
		willThrow(new NaverSearchException(NaverSearchErrorCode.RATE_LIMITED))
			.willThrow(new LlmException(LlmErrorCode.RATE_LIMITED))
			.given(triageService).triageNextBatch();

		scheduler.triageDaily();
		scheduler.triageDaily();

		verify(triageService, times(2)).triageNextBatch();
	}

	@Test
	@DisplayName("그 밖의 실패도 스케줄러를 멈추지 않는다")
	void continuesAfterOtherFailure() {
		willThrow(new IllegalStateException("DB 끊김")).given(triageService).triageNextBatch();

		scheduler.triageDaily();
		scheduler.triageDaily();

		verify(triageService, times(2)).triageNextBatch();
	}
}
