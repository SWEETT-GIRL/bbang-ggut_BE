package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sweetgirl.croissant.monthlyreport.service.MonthlyReportIngestRecoveryService;
import sweetgirl.croissant.personalization.repository.UserInteractionIngestDayRepository;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;

class MonthlyReportIngestRecoveryServiceTest {
	private final UserInteractionIngestDayRepository days = mock(UserInteractionIngestDayRepository.class);
	private final UserInteractionIngestCommandService ingest = mock(UserInteractionIngestCommandService.class);
	private final MonthlyReportIngestRecoveryService service = new MonthlyReportIngestRecoveryService(days, ingest);
	private final LocalDate from = LocalDate.of(2025, 12, 1);
	private final LocalDate until = from.plusMonths(1);

	@Test
	void completeMonthDoesNotRunIngestAgain() {
		given(days.findCompletedDates(from, until)).willReturn(from.datesUntil(until).toList());
		given(days.countByIngestDateGreaterThanEqualAndIngestDateLessThan(from, until)).willReturn(31L);
		assertThat(service.ensureComplete("2025-12")).isZero();
		verifyNoInteractions(ingest);
	}

	@Test
	void retriesRemainingDatesAfterPartialSuccessAndFailure() {
		Set<LocalDate> completed = new HashSet<>(from.datesUntil(until).toList());
		LocalDate first = from.plusDays(10);
		LocalDate second = from.plusDays(20);
		completed.remove(first);
		completed.remove(second);
		given(days.findCompletedDates(from, until)).willAnswer(call -> completed.stream().toList());
		given(ingest.ingestMissingDay(first)).willAnswer(call -> completed.add(first));
		given(ingest.ingestMissingDay(second)).willThrow(new IllegalStateException("ingest unavailable"))
			.willAnswer(call -> completed.add(second));
		assertThatThrownBy(() -> service.ensureComplete("2025-12")).isInstanceOf(IllegalStateException.class);
		given(days.countByIngestDateGreaterThanEqualAndIngestDateLessThan(from, until)).willReturn(31L);
		assertThat(service.ensureComplete("2025-12")).isEqualTo(1);
		verify(ingest).ingestMissingDay(first);
		verify(ingest, times(2)).ingestMissingDay(second);
		verifyNoMoreInteractions(ingest);
	}
}
