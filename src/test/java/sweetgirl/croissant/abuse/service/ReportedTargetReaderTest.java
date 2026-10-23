package sweetgirl.croissant.abuse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportedTargetReader")
class ReportedTargetReaderTest {

	@Mock
	private AbuseReportRepository abuseReportRepository;

	@InjectMocks
	private ReportedTargetReader reader;

	@Test
	@DisplayName("신고한 대상 id 로 목록을 거르고, 비로그인은 조회하지 않는다")
	void filters() {
		given(abuseReportRepository.findTargetIdsByReporter(1L, AbuseReportTargetType.RECORD,
			ReportedTargetReader.HIDING_STATUSES)).willReturn(List.of(10L));

		Set<Long> reported = reader.reportedIds(1L, AbuseReportTargetType.RECORD);
		assertThat(ReportedTargetReader.without(List.of(10L, 11L), id -> id, reported)).containsExactly(11L);
		assertThat(ReportedTargetReader.without(List.of(10L, 11L), id -> id, Set.of())).containsExactly(10L, 11L);

		assertThat(reader.reportedIds(null, AbuseReportTargetType.RECORD)).isEmpty();
		assertThat(reader.hasReported(null, AbuseReportTargetType.RECORD, 10L)).isFalse();
		then(abuseReportRepository).should().findTargetIdsByReporter(1L, AbuseReportTargetType.RECORD,
			ReportedTargetReader.HIDING_STATUSES);
	}
}
