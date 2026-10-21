package sweetgirl.croissant.searchmiss.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.searchmiss.service.SearchMissTriageCommandService;

@ExtendWith(MockitoExtension.class)
class SearchMissRetentionSchedulerTest {

	@Mock
	private SearchMissTriageCommandService commandService;

	private SearchMissRetentionScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new SearchMissRetentionScheduler(commandService, new SearchMissTriageProperties());
	}

	@Test
	@DisplayName("처리 후 보관 기간(기본 90일)이 지난 행을 지운다")
	void deleteHandled_usesRetentionDays() {
		given(commandService.deleteHandledBefore(any())).willReturn(2L);
		LocalDateTime before = LocalDateTime.now().minusDays(90);

		scheduler.deleteHandled();

		ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(commandService).deleteHandledBefore(captor.capture());
		assertThat(captor.getValue()).isBetween(before.minusSeconds(5), LocalDateTime.now().minusDays(90));
	}

	@Test
	@DisplayName("삭제가 실패해도 예외를 밖으로 내지 않는다 — 다음 날 다시 돈다")
	void deleteHandled_swallowsFailure() {
		given(commandService.deleteHandledBefore(any())).willThrow(new IllegalStateException("DB 끊김"));

		assertThatCode(() -> scheduler.deleteHandled()).doesNotThrowAnyException();
	}
}
