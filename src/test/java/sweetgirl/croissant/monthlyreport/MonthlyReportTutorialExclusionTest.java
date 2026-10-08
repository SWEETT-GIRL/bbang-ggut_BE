package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.monthlyreport.entity.MonthlyReportSourceHistory;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportSourceType;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportSourceReader;

class MonthlyReportTutorialExclusionTest {
	@Test
	void currentAndPreviousMonthCountsExcludeTutorialButAcceptLegacySnapshots() {
		MonthlyReportSourceHistoryRepository repository = mock(MonthlyReportSourceHistoryRepository.class);
		when(repository.findCoveredFrom()).thenReturn(LocalDateTime.of(2026, 8, 1, 0, 0));
		MonthlyReportSourceHistory legacy = history(1, "");
		MonthlyReportSourceHistory tutorial = history(2, ",\"recordType\":\"TUTORIAL\"");
		when(repository.findOwnerState(eq(7L), any())).thenReturn(List.of(legacy, tutorial));
		when(repository.findRecordState(eq(7L), any())).thenReturn(List.of(legacy, tutorial));
		MonthlyReportSourceReader reader = new MonthlyReportSourceReader(repository, new ObjectMapper());
		assertThat(reader.read(7L, "2026-09").records()).extracting(record -> record.id()).containsExactly(1L);
		assertThat(reader.countRecords(7L, "2026-09").records()).isEqualTo(1);
	}

	private MonthlyReportSourceHistory history(long id, String type) {
		MonthlyReportSourceHistory history = mock(MonthlyReportSourceHistory.class);
		when(history.getSourceType()).thenReturn(MonthlyReportSourceType.BREAD_RECORD);
		when(history.getPayload()).thenReturn("""
				{"id":%d,"createdAt":"2026-09-02T12:00:00","eatenAt":"2026-09-02T12:00:00","lovedBreads":[]%s}
				""".formatted(id, type));
		return history;
	}
}
