package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.RecordState;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportAggregationBasis;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportActivityPolicy;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportBreadAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportRecordAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportSourceReader;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTownAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportViewedStoreAggregationService;

class MonthlyReportLowActivityTest {

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void preparesLowActivityReportWithoutPreviousReportOrPhotos(int recordCount) {
		MonthlyReportRepository repository = mock(MonthlyReportRepository.class);
		MonthlyReportSourceReader sourceReader = mock(MonthlyReportSourceReader.class);
		MonthlyReportPublicationService publication = mock(MonthlyReportPublicationService.class);
		MonthlyReportViewedStoreAggregationService views = mock(MonthlyReportViewedStoreAggregationService.class);
		LocalDateTime monthStart = YearMonth.parse(MONTH).atDay(1).atStartOfDay();
		List<RecordState> records = recordCount == 0 ? List.of()
			: List.of(new RecordState(1L, null, null, monthStart, monthStart, null, null, List.of()));
		given(sourceReader.read(123L, MONTH)).willReturn(new MonthlyReportSourceSnapshot(monthStart,
			MonthlyReportAggregationBasis.INITIAL_AVAILABLE_SOURCES, monthStart.plusMonths(1), records,
			List.of(), Map.of(), Set.of(), Set.of(), 0));
		given(sourceReader.countRecords(123L, YearMonth.parse(MONTH).minusMonths(1).toString()))
			.willReturn(
				new MonthlyReportSourceReader.RecordCount(0, MonthlyReportAggregationBasis.INITIAL_AVAILABLE_SOURCES));
		ObjectMapper mapper = new ObjectMapper();
		try (ValidatorFactory validation = Validation.buildDefaultValidatorFactory()) {
			MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(mapper, validation.getValidator());
			MonthlyReportGenerationService generation = new MonthlyReportGenerationService(repository, codec,
				new MonthlyReportBreadAggregationService(sourceReader),
				new MonthlyReportRecordAggregationService(sourceReader, repository, codec), publication, sourceReader,
				views, new MonthlyReportTownAggregationService(), new MonthlyReportActivityPolicy());
			MonthlyReportGenerationService.Prepared prepared = generation.prepare(codec.read(PAYLOAD, 123L, MONTH),
				mapper.createObjectNode());
			MonthlyReportResponse report = prepared.report();
			assertThat(prepared.metrics().path("sourceHistory").path("aggregationBasis").asText())
				.isEqualTo("INITIAL_AVAILABLE_SOURCES");
			assertThat(report.summary())
				.isEqualTo(new MonthlyReportResponse.Summary((long)recordCount, 0L, (long)recordCount));
			assertThat(report.stores()).isEqualTo(new MonthlyReportResponse.Stores(0L, 0L, 0L, 0L));
			assertThat(report.breads()).isEmpty();
			assertThat(report.towns()).isEmpty();
			assertThat(report.comparison().previousRecords()).isZero();
			assertThat(prepared.metrics().path("recordAggregation").path("previousSource").asText())
				.isEqualTo("INITIAL_AVAILABLE_SOURCES");
			assertThat(report.comparison().currentRecords()).isEqualTo(recordCount);
			assertThat(report.activity().type()).isEqualTo(recordCount == 0 ? "seed" : "record");
			assertThat(report.activity().totalScore()).isEqualTo(recordCount * 7L);
			assertThat(codec.read(codec.write(report, 123L, MONTH), 123L, MONTH)).isEqualTo(report);
			verifyNoInteractions(publication);
		}
	}
}
