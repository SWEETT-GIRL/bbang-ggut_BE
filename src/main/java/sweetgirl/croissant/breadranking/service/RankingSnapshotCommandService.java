package sweetgirl.croissant.breadranking.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.dto.projection.VerifiedRegion;
import sweetgirl.croissant.breadlog.enums.RankingBoard;
import sweetgirl.croissant.breadlog.repository.BreadRecordRankingRepository;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.breadlog.service.BreadRecordRankingService;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.BoardRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.CategoryVotersRow;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.breadranking.repository.RankingSnapshotJdbcRepository;
import sweetgirl.croissant.breadranking.repository.RankingSnapshotJdbcRepository.RankedSubject;

/**
 * 순위 스냅샷 — 매일 00:05 KST 에 "어제 마감" 상위 100 을 판마다 남긴다. 조회는 {@link RankChangeResolver} 가 어제 것을 읽는다.
 *
 * <p>사람 판 4개는 전국, WEEKLY·RECORDS 는 인증된 구마다 한 판씩 더. 빵집 판은 순위가 열린(3표 이상) 카테고리마다 전국.
 * 같은 날을 다시 돌리면 그날 것을 통째로 바꾸고, 여러 인스턴스가 겹치면 advisory lock 으로 하나만 돈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RankingSnapshotCommandService {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	/** 이 배치 전용 advisory lock 키 — 인기도(20260818_1)·취향 적재와 겹치지 않는 임의 상수 */
	static final long SNAPSHOT_LOCK_KEY = 20261001_1L;
	static final int TOP = 100;
	static final int RETENTION_DAYS = 14;

	private final RankingSnapshotJdbcRepository snapshotRepository;
	private final BreadRecordRankingRepository rankingRepository;
	private final MemberRankingRegionRepository regionRepository;
	private final BreadBoardJdbcRepository boardRepository;
	private final BreadCategoryRepository categoryRepository;

	/** @return 저장한 행 수. 다른 인스턴스가 이미 돌고 있으면 -1 */
	public int snapshotYesterday() {
		return snapshot(LocalDate.now(ZONE));
	}

	int snapshot(LocalDate today) {
		if (!snapshotRepository.tryLock(SNAPSHOT_LOCK_KEY)) {
			log.info("[랭킹 스냅샷] 다른 인스턴스가 진행 중 — 건너뜀");
			return -1;
		}
		LocalDate snapshotDate = today.minusDays(1);
		snapshotRepository.deleteByDate(snapshotDate);
		int saved = 0;
		for (RankingBoard board : RankingBoard.values()) {
			saved += snapshotRepository.insertAll(snapshotDate, board.name(), 0, RankChangeResolver.NATIONWIDE,
				peopleRows(board, snapshotDate, null, null));
			if (!board.supportsLocal()) {
				continue;
			}
			for (VerifiedRegion region : regionRepository.findVerifiedRegions()) {
				saved += snapshotRepository.insertAll(snapshotDate, board.name(), 0,
					RankChangeResolver.regionKey(region.sido(), region.sigungu()),
					peopleRows(board, snapshotDate, region.sido(), region.sigungu()));
			}
		}
		saved += snapshotBreadBoards(snapshotDate);
		int purged = snapshotRepository.deleteBefore(today.minusDays(RETENTION_DAYS));
		log.info("[랭킹 스냅샷] date={} saved={} purged={}", snapshotDate, saved, purged);
		return saved;
	}

	private int snapshotBreadBoards(LocalDate snapshotDate) {
		List<CategoryVotersRow> voted = boardRepository.findCategoriesByVoters(1);
		Map<Long, BreadCategory> categories = categoryRepository
			.findAllById(voted.stream().map(CategoryVotersRow::categoryId).toList()).stream()
			.collect(java.util.stream.Collectors.toMap(BreadCategory::getId, category -> category));
		int saved = 0;
		for (CategoryVotersRow row : voted) {
			BreadCategory category = categories.get(row.categoryId());
			if (category == null) {
				continue;
			}
			List<BoardRow> boardRows = boardRepository.findBoard(category.getId(),
				BreadBoardQueryService.kindName(category), TOP);
			// 안 열린 판(3표 미만)은 아무도 못 봤으니 남기지 않는다
			if (boardRows.isEmpty() || boardRows.get(0).totalVotes() < BreadBoardQueryService.MIN_VOTES) {
				continue;
			}
			saved += snapshotRepository.insertAll(snapshotDate, RankChangeResolver.BREAD_BOARD, category.getId(),
				RankChangeResolver.NATIONWIDE,
				IntStream.range(0, boardRows.size())
					.mapToObj(index -> new RankedSubject(boardRows.get(index).storeId(), index + 1))
					.toList());
		}
		return saved;
	}

	/** 사람 판 상위 100 — 보는 사람 없이(viewer null) 그대로. 주간 판은 스냅샷 날짜가 속한 주. */
	private List<RankedSubject> peopleRows(RankingBoard board, LocalDate snapshotDate, String sido, String sigungu) {
		List<RankingRowProjection> rows = switch (board) {
			case WEEKLY -> rankingRepository.findRecordRanking(
				BreadRecordRankingService.weekStart(snapshotDate), sido, sigungu, null, TOP);
			case RECORDS -> rankingRepository.findRecordRanking(null, sido, sigungu, null, TOP);
			case REGIONS -> rankingRepository.findRegionRanking(null, TOP);
			case SHARE -> rankingRepository.findShareRanking(null, TOP);
		};
		return rows.stream()
			.filter(row -> row.getRank() <= TOP)
			.map(row -> new RankedSubject(row.getMemberId(), row.getRank().intValue()))
			.toList();
	}
}
