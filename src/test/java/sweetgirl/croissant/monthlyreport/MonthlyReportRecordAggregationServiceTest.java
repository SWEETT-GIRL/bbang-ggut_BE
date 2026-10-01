package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository.MonthlyRecordCounts;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportRecordAggregationService;

class MonthlyReportRecordAggregationServiceTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private final BreadRecordRepository records = mock(BreadRecordRepository.class);
	private final MonthlyReportRepository reports = mock(MonthlyReportRepository.class);
	private final MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(new ObjectMapper(),
		FACTORY.getValidator());
	private final MonthlyReportRecordAggregationService service = new MonthlyReportRecordAggregationService(
		records, reports, codec);

	@AfterAll
	static void closeValidator() {
		FACTORY.close();
	}

	private void currentCounts() {
		MonthlyRecordCounts counts = mock(MonthlyRecordCounts.class);
		given(counts.getRecords()).willReturn(6L);
		given(counts.getStores()).willReturn(5L);
		given(records.countMonthlyReportRecords(eq(123L), any(), any())).willReturn(counts);
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
		assertThat(codec.read(codec.write(report, 123L, MONTH), 123L, MONTH)).isEqualTo(report);
	}

	@ParameterizedTest
	@ValueSource(strings = {"MISSING", "WITHHELD", "FAILED"})
	void unpublishedPreviousMonthDoesNotPreventCurrentReport(String status) {
		currentCounts();
		Optional<MonthlyReportMember> snapshot = status.equals("MISSING") ? Optional.empty()
			: Optional.of(MonthlyReportFixtures.snapshot(status, null));
		given(reports.findById(new MonthlyReportMemberId(123L, "2025-11"))).willReturn(snapshot);
		assertThat(service.aggregate(123L, MONTH))
			.isEqualTo(new MonthlyReportRecordAggregationService.Result(6, 5, null));
	}
}
