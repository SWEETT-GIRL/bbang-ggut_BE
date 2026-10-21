package sweetgirl.croissant.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.ranking.repository.RankingSnapshotRunJdbcRepository;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;
import sweetgirl.croissant.ranking.snapshot.RankingSnapshotSource;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

@ExtendWith(MockitoExtension.class)
class RankingSnapshotCommandServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
	private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 30);

	@Mock
	private RankingSnapshotRunJdbcRepository runRepository;
	@Mock
	private RankingSnapshotWriter writer;
	@Mock
	private RankingSnapshotSource peopleSource;
	@Mock
	private RankingSnapshotSource breadSource;

	private RankingSnapshotCommandService service;

	@BeforeEach
	void setUp() {
		service = new RankingSnapshotCommandService(runRepository, writer, List.of(peopleSource, breadSource));
	}

	@Test
	@DisplayName("그 날짜를 다른 실행이 끝냈거나 진행 중이면(선점 실패) 아무것도 안 하고 -1")
	void skipsWhenNotLeased() {
		given(runRepository.tryStart(YESTERDAY)).willReturn(false);

		assertThat(service.snapshot(TODAY)).isEqualTo(-1);
		verify(writer, never()).replace(any(), any());
		verify(peopleSource, never()).boards(any());
	}

	@Test
	@DisplayName("어제 날짜를 선점한 뒤 소스가 낸 판을 하나씩 따로 쓰고, 14일 지난 것을 지우고, 결과를 기록한다")
	void writesEachBoardSeparately() {
		SnapshotBoard weekly = board("WEEKLY", 0, "");
		SnapshotBoard local = board("RECORDS", 0, "서울_동대문구");
		SnapshotBoard bread = board("BREAD", 1, "");
		given(runRepository.tryStart(YESTERDAY)).willReturn(true);
		given(peopleSource.boards(YESTERDAY)).willReturn(List.of(weekly, local));
		given(breadSource.boards(YESTERDAY)).willReturn(List.of(bread));
		given(writer.replace(YESTERDAY, weekly)).willReturn(100);
		given(writer.replace(YESTERDAY, local)).willReturn(7);
		given(writer.replace(YESTERDAY, bread)).willReturn(3);
		given(writer.purgeBefore(TODAY.minusDays(14))).willReturn(500);

		int saved = service.snapshot(TODAY);

		assertThat(saved).isEqualTo(110);
		verify(writer).replace(YESTERDAY, weekly);
		verify(writer).replace(YESTERDAY, local);
		verify(writer).replace(YESTERDAY, bread);
		verify(writer).purgeBefore(TODAY.minusDays(14));
		verify(runRepository).finish(YESTERDAY, 110, 0);
	}

	@Test
	@DisplayName("한 판이 깨져도 나머지 판은 쓰고, 깨진 수를 기록한다")
	void keepsGoingWhenOneBoardFails() {
		SnapshotBoard weekly = board("WEEKLY", 0, "");
		SnapshotBoard share = board("SHARE", 0, "");
		given(runRepository.tryStart(YESTERDAY)).willReturn(true);
		given(peopleSource.boards(YESTERDAY)).willReturn(List.of(weekly, share));
		given(breadSource.boards(YESTERDAY)).willReturn(List.of());
		given(writer.replace(YESTERDAY, weekly)).willThrow(new IllegalStateException("boom"));
		given(writer.replace(YESTERDAY, share)).willReturn(50);

		int saved = service.snapshot(TODAY);

		assertThat(saved).isEqualTo(50);
		verify(runRepository).finish(eq(YESTERDAY), eq(50), eq(1));
	}

	private static SnapshotBoard board(String name, long categoryId, String region) {
		return new SnapshotBoard(name, categoryId, region, () -> List.of(new RankedSubject(1L, 1)));
	}
}
