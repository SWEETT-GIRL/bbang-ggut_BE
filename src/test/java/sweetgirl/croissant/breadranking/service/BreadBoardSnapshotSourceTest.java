package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.BoardRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.CategoryVotersRow;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

@ExtendWith(MockitoExtension.class)
class BreadBoardSnapshotSourceTest {

	private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 9, 30);
	private static final LocalDateTime VOTED_AT = LocalDateTime.of(2026, 9, 30, 12, 0);

	@Mock
	private BreadBoardJdbcRepository boardRepository;
	@Mock
	private BreadBoardQueryService boardQueryService;

	@InjectMocks
	private BreadBoardSnapshotSource source;

	@Test
	@DisplayName("표가 있는 종류마다 전국 판 하나 — 열린 판은 순위 번호를 붙이고, 안 열린 판(3표 미만)은 빈 줄")
	void boardsPerVotedCategory() {
		BreadCategory salt = category(1L, MenuKind.소금빵);
		BreadCategory bagel = category(2L, MenuKind.베이글);
		given(boardRepository.findCategoriesByVoters(1)).willReturn(List.of(
			new CategoryVotersRow(1L, 5, 5), new CategoryVotersRow(2L, 2, 2), new CategoryVotersRow(9L, 1, 1)));
		given(boardQueryService.categoriesOf(List.of(1L, 2L, 9L))).willReturn(Map.of(1L, salt, 2L, bagel));
		given(boardRepository.findBoard(1L, "소금빵", 100)).willReturn(List.of(
			new BoardRow(10L, 3, VOTED_AT, 0, 0, null, 5), new BoardRow(20L, 2, VOTED_AT, 0, 0, null, 5)));
		given(boardRepository.findBoard(2L, "베이글", 100)).willReturn(List.of(
			new BoardRow(30L, 2, VOTED_AT, 0, 0, null, 2)));

		List<SnapshotBoard> boards = source.boards(SNAPSHOT_DATE);

		assertThat(boards).hasSize(2);
		assertThat(boards).extracting(SnapshotBoard::board).containsOnly("BREAD");
		assertThat(boards.get(0).categoryId()).isEqualTo(1L);
		assertThat(boards.get(0).rows().get())
			.containsExactly(new RankedSubject(10L, 1), new RankedSubject(20L, 2));
		assertThat(boards.get(1).rows().get()).isEmpty();
	}

	private static BreadCategory category(Long id, MenuKind kind) {
		BreadCategory category = BreadCategory.custom(kind.name(), BreadGroup.빵, null);
		ReflectionTestUtils.setField(category, "id", id);
		ReflectionTestUtils.setField(category, "menuKind", kind);
		return category;
	}
}
