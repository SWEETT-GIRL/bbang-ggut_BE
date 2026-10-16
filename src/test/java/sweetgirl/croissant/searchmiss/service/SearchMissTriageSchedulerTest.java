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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;

import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageSchedulerTest {

	@Mock
	private SearchMissTriageService triageService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private SearchMissTriageScheduler scheduler;

	@BeforeEach
	void setUp() {
		SearchMissTriageProperties properties = new SearchMissTriageProperties();
		properties.setEnabled(true);
		scheduler = new SearchMissTriageScheduler(triageService, properties, eventPublisher);
	}

	private OperationalNotificationEvent publishedReport() {
		ArgumentCaptor<OperationalNotificationEvent> captor = ArgumentCaptor
			.forClass(OperationalNotificationEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("꺼져 있으면 배치도 리포트도 없다 — 기본값이 꺼짐이다")
	void skipsWhenDisabled() {
		SearchMissTriageScheduler defaultScheduler = new SearchMissTriageScheduler(triageService,
			new SearchMissTriageProperties(), eventPublisher);

		defaultScheduler.triageDaily();

		verify(triageService, never()).triageNextBatch();
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	@DisplayName("판정할 행이 없어도 리포트를 보낸다 — 리포트가 없으면 안 돈 것이다")
	void reportsEvenWhenNothingToTriage() {
		given(triageService.triageNextBatch())
			.willReturn(new SearchMissTriageBatchResult(0, List.of(), List.of(), null));

		scheduler.triageDaily();

		OperationalNotificationEvent report = publishedReport();
		assertThat(report.channel()).isEqualTo(Channel.SEARCH_MISS);
		assertThat(report.title()).contains("완료");
		assertThat(report.description()).contains("판정할 검색어가 없었습니다");
	}

	@Test
	@DisplayName("배치가 예외로 끝나면 실패 리포트를 보내고 스케줄러는 멈추지 않는다")
	void reportsFailure() {
		willThrow(new IllegalStateException("DB 끊김")).given(triageService).triageNextBatch();

		scheduler.triageDaily();

		OperationalNotificationEvent report = publishedReport();
		assertThat(report.title()).contains("실패");
		assertThat(report.fields()).singleElement()
			.satisfies(field -> assertThat(field.value()).contains("DB 끊김"));
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
	@DisplayName("하루 한 번 새벽 6시(KST)에 돈다 — 설정으로 바꿀 수 있다")
	void runsDailyAtSixInSeoul() throws NoSuchMethodException {
		Method method = SearchMissTriageScheduler.class.getMethod("triageDaily");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled.cron()).isEqualTo("${search-miss.triage.cron:0 0 6 * * *}");
		assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
	}
}
