package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.batch.job.BatchJob;
import sweetgirl.croissant.external.slack.dto.SlackMessage;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageSchedulerTest {

	@Mock
	private SearchMissTriageService triageService;

	@Mock
	private SearchMissTriageReportSender reportSender;

	private SearchMissTriageScheduler scheduler;

	@BeforeEach
	void setUp() {
		SearchMissTriageProperties properties = new SearchMissTriageProperties();
		properties.setEnabled(true);
		scheduler = new SearchMissTriageScheduler(triageService, properties, reportSender);
	}

	@SuppressWarnings("unchecked")
	private List<SlackMessage> sentReport() {
		ArgumentCaptor<List<SlackMessage>> captor = ArgumentCaptor.forClass(List.class);
		verify(reportSender).send(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("꺼져 있으면 배치도 리포트도 없다 — 기본값이 꺼짐이다")
	void skipsWhenDisabled() {
		SearchMissTriageScheduler defaultScheduler = new SearchMissTriageScheduler(triageService,
			new SearchMissTriageProperties(), reportSender);

		defaultScheduler.triageDaily();

		verify(triageService, never()).triageNextBatch();
		verify(reportSender, never()).send(any());
	}

	@Test
	@DisplayName("판정할 행이 없어도 리포트를 보낸다 — 리포트가 없으면 안 돈 것이다")
	void reportsEvenWhenNothingToTriage() {
		given(triageService.triageNextBatch())
			.willReturn(new SearchMissTriageBatchResult(0, List.of(), List.of(), null));

		scheduler.triageDaily();

		assertThat(sentReport()).singleElement().satisfies(message -> {
			assertThat(message.title()).contains("완료");
			assertThat(message.description()).contains("판정할 검색어가 없었습니다");
		});
	}

	@Test
	@DisplayName("배치가 예외로 끝나면 실패 리포트를 보내고 스케줄러는 멈추지 않는다")
	void reportsFailure() {
		willThrow(new IllegalStateException("DB 끊김")).given(triageService).triageNextBatch();

		scheduler.triageDaily();

		assertThat(sentReport()).singleElement().satisfies(message -> {
			assertThat(message.title()).contains("실패");
			assertThat(message.fields()).singleElement()
				.satisfies(field -> assertThat(field.value()).contains("DB 끊김"));
		});
	}

	@Test
	@DisplayName("다음 날 회차는 전날 실패와 상관없이 돈다")
	void runsAgainAfterFailure() {
		willThrow(new IllegalStateException("DB 끊김")).given(triageService).triageNextBatch();

		scheduler.triageDaily();
		scheduler.triageDaily();

		verify(triageService, times(2)).triageNextBatch();
	}

	@Test
	@DisplayName("배치 작업 search-miss-triage 로 실행된다 — 시각은 EventBridge 규칙이 정한다")
	void runsAsSearchMissTriageBatchJob() throws NoSuchMethodException {
		Method method = SearchMissTriageScheduler.class.getMethod("triageDaily");

		assertThat(method.getAnnotation(BatchJob.class).value()).isEqualTo("search-miss-triage");
	}
}
