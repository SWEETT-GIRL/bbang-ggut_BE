package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.dto.projection.BreadRecordActivityProjection;
import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class BreadRecordActivityQueryServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	BreadRecordRepository repository;
	BreadRecordActivityQueryService service;
	private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-09T00:00:00");

	@BeforeEach
	void setUp() {
		service = new BreadRecordActivityQueryService(memberReader, repository,
			Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void noRecordsStartsAtOneAndUsesKstNotClockZone() {
		givenRecords(List.of());
		var result = service.getActivity(1L);
		assertThat(result.asOfDate()).isEqualTo(LocalDate.parse("2026-10-09"));
		assertThat(result.computedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
		assertThat(result.currentStreakDays()).isZero();
		assertThat(result.nextStreakDays()).isOne();
		assertThat(result.recordedToday()).isFalse();
		assertThat(result.lastRecordedDate()).isNull();
		assertThat(result.latestNormalRecordId()).isNull();
	}

	@Test
	void yesterdayTutorialContinuesAsDayTwo() {
		givenRecords(List.of(record(1L, "2026-10-08", RecordType.TUTORIAL)));
		var result = service.getActivity(1L);
		assertThat(result.currentStreakDays()).isOne();
		assertThat(result.nextStreakDays()).isEqualTo(2);
		assertThat(result.activeRecordCount()).isOne();
		assertThat(result.normalRecordCount()).isZero();
		verify(memberReader).getMember(1L);
	}

	@Test
	void multipleRecordsPerDayCountOnceAndEatenDateDoesNotChangeStreak() {
		givenRecords(List.of(record(1L, "2026-10-07", RecordType.TUTORIAL),
			record(2L, "2026-10-08", RecordType.NORMAL), record(3L, "2026-10-09", RecordType.NORMAL),
			record(4L, "2026-10-09", RecordType.NORMAL)));
		var result = service.getActivity(1L);
		assertThat(result.recordedToday()).isTrue();
		assertThat(result.currentStreakDays()).isEqualTo(3);
		assertThat(result.nextStreakDays()).isEqualTo(3);
		assertThat(result.activeRecordCount()).isEqualTo(4);
		assertThat(result.normalRecordCount()).isEqualTo(3);
		assertThat(result.latestNormalRecordId()).isEqualTo(4);
		assertThat(result.latestNormalEatenDate()).isEqualTo(LocalDate.parse("2026-09-01"));
	}

	@Test
	void gapResetsStreakAndDoesNotEraseHistory() {
		givenRecords(List.of(record(1L, "2026-10-07", RecordType.NORMAL)));
		var result = service.getActivity(1L);
		assertThat(result.currentStreakDays()).isZero();
		assertThat(result.nextStreakDays()).isOne();
		assertThat(result.lastRecordedDate()).isEqualTo(LocalDate.parse("2026-10-07"));
	}

	@Test
	void noTwoThousandRecordLimit() {
		givenRecords(IntStream.range(0, 2100).mapToObj(index -> record((long)index,
			NOW.toLocalDate().minusDays(index).toString(), RecordType.NORMAL)).toList());
		assertThat(service.getActivity(1L).currentStreakDays()).isEqualTo(2100);
	}

	@Test
	void unauthorizedDoesNotReadRecords() {
		when(memberReader.getMember(null)).thenThrow(new MemberException(MemberErrorCode.UNAUTHORIZED));
		assertThatThrownBy(() -> service.getActivity(null)).isInstanceOf(MemberException.class);
		verifyNoInteractions(repository);
	}

	private void givenRecords(List<BreadRecordActivityProjection> records) {
		when(repository.findAllByMemberIdAndDeletedAtIsNullAndCreatedAtLessThanEqual(1L, NOW)).thenReturn(records);
	}

	private BreadRecordActivityProjection record(Long id, String date, RecordType type) {
		return new ActivityRow(id, LocalDate.parse(date).atStartOfDay(),
			LocalDateTime.parse("2026-09-01T12:00:00"), type);
	}

	private record ActivityRow(Long getId, LocalDateTime getCreatedAt, LocalDateTime getEatenAt,
			RecordType getRecordType) implements BreadRecordActivityProjection {
	}
}
