package sweetgirl.croissant.breadranking.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.BoardRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.CategoryVotersRow;
import sweetgirl.croissant.ranking.service.RankChangeResolver;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;
import sweetgirl.croissant.ranking.snapshot.RankingSnapshotSource;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

/**
 * 빵집 판의 스냅샷 — 표가 하나라도 있는 종류마다 전국 상위 100. 안 열린 판(3표 미만)은 빈 줄을 내서 그날 것을 지우기만 한다
 * (아무도 못 본 순위와는 비교하지 않는다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadBoardSnapshotSource implements RankingSnapshotSource {

	static final int TOP = 100;

	private final BreadBoardJdbcRepository boardRepository;
	private final BreadBoardQueryService boardQueryService;

	@Override
	public List<SnapshotBoard> boards(LocalDate snapshotDate) {
		List<CategoryVotersRow> voted = boardRepository.findCategoriesByVoters(1);
		Map<Long, BreadCategory> categories = boardQueryService.categoriesOf(
			voted.stream().map(CategoryVotersRow::categoryId).toList());
		return voted.stream()
			.map(row -> categories.get(row.categoryId()))
			.filter(category -> category != null)
			.map(category -> new SnapshotBoard(RankChangeResolver.BREAD_BOARD, category.getId(),
				RankChangeResolver.NATIONWIDE, () -> rows(category)))
			.toList();
	}

	private List<RankedSubject> rows(BreadCategory category) {
		List<BoardRow> boardRows = boardRepository.findBoard(category.getId(),
			BreadBoardQueryService.kindName(category), TOP);
		if (boardRows.isEmpty() || boardRows.get(0).totalVotes() < BreadBoardQueryService.MIN_VOTES) {
			return List.of();
		}
		return IntStream.range(0, boardRows.size())
			.mapToObj(index -> new RankedSubject(boardRows.get(index).storeId(), index + 1))
			.toList();
	}
}
