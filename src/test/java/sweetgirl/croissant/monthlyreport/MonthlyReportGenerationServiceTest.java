package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportBreadAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportRecordAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportSourceReader;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTownAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportViewedStoreAggregationService;

class MonthlyReportGenerationServiceTest {

	@Test
	void publishedMonthDoesNotReadBreadLogsOrRequestPublicationAgain() {
		var repository = mock(MonthlyReportRepository.class);
		var aggregation = mock(MonthlyReportBreadAggregationService.class);
		var sourceReader = mock(MonthlyReportSourceReader.class);
		var records = mock(MonthlyReportRecordAggregationService.class);
		var publication = mock(MonthlyReportPublicationService.class);
		var views = mock(MonthlyReportViewedStoreAggregationService.class);
		var towns = mock(MonthlyReportTownAggregationService.class);
		given(repository.findById(new MonthlyReportMemberId(123L, MONTH)))
			.willReturn(Optional.of(MonthlyReportFixtures.snapshot("PUBLISHED", PAYLOAD)));
		var mapper = new ObjectMapper();
		try (var validation = Validation.buildDefaultValidatorFactory()) {
			var codec = new MonthlyReportPayloadCodec(mapper, validation.getValidator());
			var generation = new MonthlyReportGenerationService(repository, codec, aggregation, records, publication,
				sourceReader, views, towns);
			assertThat(generation.publishFromDraft(codec.read(PAYLOAD, 123L, MONTH),
				mapper.createObjectNode().put("records", 12), "test-v2")).isFalse();
			verifyNoInteractions(aggregation, records, publication, sourceReader, views, towns);
		}
	}
}
