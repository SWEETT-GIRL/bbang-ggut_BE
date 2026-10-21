package sweetgirl.croissant.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.ranking.repository.RankingSnapshotJdbcRepository;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

@ExtendWith(MockitoExtension.class)
class RankingSnapshotWriterTest {

	private static final LocalDate DATE = LocalDate.of(2026, 9, 30);

	@Mock
	private RankingSnapshotJdbcRepository snapshotRepository;

	@InjectMocks
	private RankingSnapshotWriter writer;

	@Test
	@DisplayName("판 하나 — 줄을 읽고, 그날 것을 지운 뒤, 다시 넣는다")
	void replaceDeletesThenInserts() {
		List<RankedSubject> rows = List.of(new RankedSubject(7L, 1), new RankedSubject(8L, 2));
		SnapshotBoard board = new SnapshotBoard("RECORDS", 0, "서울_동대문구", () -> rows);
		given(snapshotRepository.insertAll(DATE, "RECORDS", 0, "서울_동대문구", rows)).willReturn(2);

		int saved = writer.replace(DATE, board);

		assertThat(saved).isEqualTo(2);
		InOrder order = inOrder(snapshotRepository);
		order.verify(snapshotRepository).deleteBoard(DATE, "RECORDS", 0, "서울_동대문구");
		order.verify(snapshotRepository).insertAll(DATE, "RECORDS", 0, "서울_동대문구", rows);
	}

	@Test
	@DisplayName("줄이 없는 판(안 열린 빵집 판)은 지우기만 한다")
	void replaceWithNoRowsOnlyDeletes() {
		SnapshotBoard board = new SnapshotBoard("BREAD", 3, "", List::of);
		given(snapshotRepository.insertAll(DATE, "BREAD", 3, "", List.of())).willReturn(0);

		assertThat(writer.replace(DATE, board)).isZero();
		inOrder(snapshotRepository).verify(snapshotRepository).deleteBoard(DATE, "BREAD", 3, "");
	}
}
