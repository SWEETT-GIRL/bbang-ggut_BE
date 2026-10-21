package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.RecordState;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportAggregationBasis;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportRecordAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportSourceReader;

class MonthlyReportRecordAggregationServiceTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private final MonthlyReportSourceReader sourceReader = mock(MonthlyReportSourceReader.class);
	private final MonthlyReportRepository reports = mock(MonthlyReportRepository.class);
	private final MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(new ObjectMapper(),
		FACTORY.getValidator());
	private final MonthlyReportRecordAggregationService service = new MonthlyReportRecordAggregationService(
		sourceReader, reports, codec);

	@AfterAll
	static void closeValidator() {
		FACTORY.close();
	}

	private void currentCounts() {
		LocalDateTime time = LocalDateTime.of(2025, 12, 1, 0, 0);
		List<RecordState> records = new ArrayList<>();
		Long[] stores = {11L, 11L, null, null, 12L, 13L};
		for (int i = 0; i < stores.length; i++) {
			records.add(new RecordState((long)i + 1, stores[i], null, time, time, null, null, List.of()));
		}
		given(sourceReader.read(123L, MONTH)).willReturn(
			new MonthlyReportSourceSnapshot(time, MonthlyReportAggregationBasis.MONTH_END_HISTORY, time, records,
				List.of(), Map.of(), Set.of(), Set.of(), 0));
	}

	@Test
	void comparesWithPublishedPreviousSnapshotAndReplacesDraftCounts() {
		currentCounts();
		String previousPayload = PAYLOAD.replace(MONTH, "2025-11");
		given(reports.findById(new MonthlyReportMemberId(123L, "2025-11")))
			.willReturn(Optional.of(MonthlyReportFixtures.snapshot("PUBLISHED", previousPayload)));
		MonthlyReportResponse report = service.aggregate(123L, MONTH).applyTo(codec.read(PAYLOAD, 123L, MONTH));
		assertThat(report.summary().records()).isEqualTo(6);
		assertThat(report.summary().stores()).isEqualTo(5);
		assertThat(report.comparison()).isEqualTo(new MonthlyReportResponse.Comparison(12L, 6L));
		verify(sourceReader, never()).countRecords(123L, "2025-11");
		assertThat(codec.read(codec.write(report, 123L, MONTH), 123L, MONTH)).isEqualTo(report);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MISSING", "WITHHELD", "FAILED"})
	void unpublishedPreviousMonthUsesRecordHistory(String status) {
		currentCounts();
		Optional<MonthlyReportMember> snapshot = status.equals("MISSING") ? Optional.empty()
			: Optional.of(MonthlyReportFixtures.snapshot(status, null));
		given(reports.findById(new MonthlyReportMemberId(123L, "2025-11"))).willReturn(snapshot);
		given(sourceReader.countRecords(123L, "2025-11")).willReturn(new MonthlyReportSourceReader.RecordCount(
			3L, MonthlyReportAggregationBasis.INITIAL_AVAILABLE_SOURCES));
		assertThat(service.aggregate(123L, MONTH))
			.isEqualTo(new MonthlyReportRecordAggregationService.Result(6, 5, 3L, "INITIAL_AVAILABLE_SOURCES"));
	}

	@Test
	void zeroPreviousActivityIsComparable() {
		currentCounts();
		given(sourceReader.countRecords(123L, "2025-11")).willReturn(new MonthlyReportSourceReader.RecordCount(
			0L, MonthlyReportAggregationBasis.MONTH_END_HISTORY));
		assertThat(service.aggregate(123L, MONTH))
			.isEqualTo(new MonthlyReportRecordAggregationService.Result(6, 5, 0L, "MONTH_END_HISTORY"));
	}

	@Test
	void unavailablePreviousHistoryKeepsCurrentReportWithoutInventingZero() {
		currentCounts();
		given(sourceReader.countRecords(123L, "2025-11"))
			.willThrow(new CustomException(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE));
		assertThat(service.aggregate(123L, MONTH))
			.isEqualTo(new MonthlyReportRecordAggregationService.Result(6, 5, null, "unavailable"));
	}

	@Test
	void unexpectedPreviousAggregationFailureIsNotHidden() {
		currentCounts();
		given(sourceReader.countRecords(123L, "2025-11"))
			.willThrow(new CustomException(MonthlyReportErrorCode.GENERATION_FAILED));
		assertThatThrownBy(() -> service.aggregate(123L, MONTH)).isInstanceOf(CustomException.class);
	}
}
