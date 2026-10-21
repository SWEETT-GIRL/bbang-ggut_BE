package sweetgirl.croissant.breadlog.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.dto.projection.VerifiedRegion;
import sweetgirl.croissant.breadlog.enums.RankingBoard;
import sweetgirl.croissant.breadlog.repository.BreadRecordRankingRepository;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.ranking.service.RankChangeResolver;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;
import sweetgirl.croissant.ranking.snapshot.RankingSnapshotSource;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

/**
 * 빵순이 4판의 스냅샷 — 전국 + WEEKLY·RECORDS 는 인증된 구마다 한 판씩 더. 보는 사람 없이(viewer null) 상위 100.
 * 주간 판은 스냅샷 날짜가 속한 주다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PeopleRankingSnapshotSource implements RankingSnapshotSource {

	static final int TOP = 100;

	private final BreadRecordRankingRepository rankingRepository;
	private final MemberRankingRegionRepository regionRepository;

	@Override
	public List<SnapshotBoard> boards(LocalDate snapshotDate) {
		List<VerifiedRegion> regions = regionRepository.findVerifiedRegions();
		List<SnapshotBoard> boards = new ArrayList<>();
		for (RankingBoard board : RankingBoard.values()) {
			boards.add(new SnapshotBoard(board.name(), 0, RankChangeResolver.NATIONWIDE,
				() -> rows(board, snapshotDate, null, null)));
			if (!board.supportsLocal()) {
				continue;
			}
			for (VerifiedRegion region : regions) {
				boards.add(new SnapshotBoard(board.name(), 0,
					RankChangeResolver.regionKey(region.sido(), region.sigungu()),
					() -> rows(board, snapshotDate, region.sido(), region.sigungu())));
			}
		}
		return boards;
	}

	private List<RankedSubject> rows(RankingBoard board, LocalDate snapshotDate, String sido, String sigungu) {
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
