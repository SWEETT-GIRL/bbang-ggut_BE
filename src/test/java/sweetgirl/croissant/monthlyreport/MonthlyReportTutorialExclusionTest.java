package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
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
		MonthlyReportSourceHistory normal = history(3, ",\"recordType\":\"NORMAL\"");
		MonthlyReportSourceHistory legacyNull = history(4, ",\"recordType\":null");
		when(repository.findOwnerState(eq(7L), any())).thenReturn(List.of(legacy, tutorial, normal, legacyNull));
		when(repository.findRecordState(eq(7L), any())).thenReturn(List.of(legacy, tutorial, normal, legacyNull));
		MonthlyReportSourceReader reader = new MonthlyReportSourceReader(repository, new ObjectMapper());
		var records = reader.read(7L, "2026-09").records();
		assertThat(records).extracting(record -> record.id()).containsExactly(1L, 3L, 4L);
		assertThat(records).allSatisfy(record -> assertThat(record.recordType()).isEqualTo(RecordType.NORMAL));
		assertThat(reader.countRecords(7L, "2026-09").records()).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"FUTURE_TYPE\"", "\"normal\"", "\"\"", "0", "1", "\"0\""})
	void invalidRecordTypeFailsInsteadOfCountingAsNormal(String value) {
		MonthlyReportSourceHistoryRepository repository = mock(MonthlyReportSourceHistoryRepository.class);
		when(repository.findCoveredFrom()).thenReturn(LocalDateTime.of(2026, 8, 1, 0, 0));
		MonthlyReportSourceHistory invalid = history(1, ",\"recordType\":" + value);
		when(repository.findOwnerState(eq(7L), any())).thenReturn(List.of(invalid));
		when(repository.findRecordState(eq(7L), any())).thenReturn(List.of(invalid));
		// 전역 mapper가 관대한 설정이어도 원천 이력은 엄격하게 검증해야 한다.
		ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
		MonthlyReportSourceReader reader = new MonthlyReportSourceReader(repository, mapper);
		assertThatThrownBy(() -> reader.read(7L, "2026-09")).isInstanceOfSatisfying(CustomException.class,
			exception -> assertThat(exception.getErrorCode())
				.isEqualTo(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE));
		assertThatThrownBy(() -> reader.countRecords(7L, "2026-09")).isInstanceOfSatisfying(CustomException.class,
			exception -> assertThat(exception.getErrorCode())
				.isEqualTo(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE));
		assertThat(mapper.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)).isTrue();
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
