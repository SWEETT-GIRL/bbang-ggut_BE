package sweetgirl.croissant.breadlog.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse.Entry;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse.Me;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse.Next;
import sweetgirl.croissant.breadlog.dto.response.RankingRegionResponse;
import sweetgirl.croissant.breadlog.enums.RankingBoard;
import sweetgirl.croissant.breadlog.enums.RankingScope;
import sweetgirl.croissant.breadlog.repository.BreadRecordRankingRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.ranking.service.RankChangeResolver;
import sweetgirl.croissant.ranking.service.RankChanges;
import sweetgirl.croissant.reward.service.RankingRewardService;

/**
 * 빵로그 랭킹 — 판·범위에 맞는 쿼리를 골라 부르고, 내 자리와 "N개 더 하면 몇 위"를 붙인다.
 *
 * <p>요청마다 바로 센다. 사람이 많아져 느려지면 인기도 점수처럼 미리 계산해 두는 배치로 바꾼다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BreadRecordRankingService {

	static final int MAX_LIMIT = 100;

	private final MemberReader memberReader;
	private final BreadRecordRankingRepository rankingRepository;
	private final RankingRegionService regionService;
	private final RankChangeResolver rankChangeResolver;
	private final RankingRewardService rankingRewardService;

	public BreadRecordRankingResponse getRanking(Long viewerId, RankingBoard board, RankingScope scope, int limit) {
		boolean guest = memberReader.getMember(viewerId).isGuest();
		int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
		// 내 지역은 앱이 보내지 않고 서버에 인증된 것을 쓴다 — 여러 지역 판을 돌아가며 보지 못하게
		RankingRegionResponse region = scope == RankingScope.LOCAL && board.supportsLocal()
			? regionService.requireRegion(viewerId)
			: null;
		String sido = region == null ? null : region.sido();
		String sigungu = region == null ? null : region.sigungu();
		List<RankingRowProjection> rows = switch (board) {
			case WEEKLY -> rankingRepository.findRecordRanking(weekStart(LocalDate.now(RankingRegionService.ZONE)),
				sido, sigungu, viewerId, lim);
			case RECORDS -> rankingRepository.findRecordRanking(null, sido, sigungu, viewerId, lim);
			case REGIONS -> rankingRepository.findRegionRanking(viewerId, lim);
			case SHARE -> rankingRepository.findShareRanking(viewerId, lim);
		};
		RankChanges changes = rankChangeResolver.resolve(board.name(), 0,
			RankChangeResolver.regionKey(sido, sigungu), LocalDate.now(RankingRegionService.ZONE));
		BreadRecordRankingResponse response = assemble(rows, viewerId, guest, lim, sido, sigungu, changes,
			rankingRewardService.crownedMemberIds());
		return region == null ? response : response.withRegionMemberCount(regionService.countMembers(sido, sigungu));
	}

	/** 이번 주 월요일 0시(Asia/Seoul). DB 의 created_at 도 같은 벽시계다. */
	public static LocalDateTime weekStart(LocalDate today) {
		return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
	}

	static BreadRecordRankingResponse assemble(
			List<RankingRowProjection> rows, Long viewerId, boolean guest, int limit, String sido,
			String sigungu, RankChanges changes) {
		return assemble(rows, viewerId, guest, limit, sido, sigungu, changes, Set.of());
	}

	/** @param crownedMemberIds 지난주 전국 주간 1 위라 왕관을 쓰고 있는 회원들. 어느 판에서든 그 사람 줄에 왕관이 붙는다 */
	static BreadRecordRankingResponse assemble(
			List<RankingRowProjection> rows, Long viewerId, boolean guest, int limit, String sido,
			String sigungu, RankChanges changes, Set<Long> crownedMemberIds) {
		List<Entry> entries = rows.stream()
			.filter(row -> row.getRank() <= limit)
			.map(row -> new Entry(row.getRank(), row.getNickname(), row.getSelectedCharacter(), row.getScore(),
				Objects.equals(row.getMemberId(), viewerId),
				changes.changeOf(row.getMemberId(), row.getRank()), changes.isNew(row.getMemberId()),
				crownedMemberIds.contains(row.getMemberId()), row.getMemberId()))
			.toList();
		if (guest) {
			return new BreadRecordRankingResponse(sido, sigungu, entries, null);
		}
		RankingRowProjection mine = rows.stream()
			.filter(row -> Objects.equals(row.getMemberId(), viewerId))
			.findFirst()
			.orElse(null);
		if (mine == null) {
			return new BreadRecordRankingResponse(sido, sigungu, entries, new Me(null, 0, null));
		}
		return new BreadRecordRankingResponse(sido, sigungu, entries,
			new Me(mine.getRank(), mine.getScore(), next(rows, mine)));
	}

	/**
	 * 바로 위 사람을 확실히 넘는 목표. 점수만 같아지면 동점 기준에서 밀릴 수 있어 "위 사람 점수 + 1"을 잡는다.
	 * 그 점수면 위 사람과 같은 점수 무리 전체를 넘으니, 오를 순위는 그 무리의 맨 앞 순위다.
	 */
	private static Next next(List<RankingRowProjection> rows, RankingRowProjection mine) {
		if (mine.getRank() == 1) {
			return null;
		}
		return rows.stream()
			.filter(row -> row.getRank() == mine.getRank() - 1)
			.findFirst()
			.map(ahead -> new Next(ahead.getScore() - mine.getScore() + 1, ahead.getScoreFirstRank()))
			.orElse(null);
	}
}
