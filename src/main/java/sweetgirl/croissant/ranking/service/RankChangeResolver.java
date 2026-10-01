package sweetgirl.croissant.ranking.service;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.ranking.repository.RankingSnapshotJdbcRepository;

/**
 * 조회 때 어제 스냅샷을 읽어 변동을 만든다. 사람 판은 {@code RankingBoard} 이름, 빵 판은 {@link #BREAD_BOARD}.
 * 지역은 '' = 전국, 내 지역 판은 '서울_동대문구' 꼴(member_ranking_region 의 sido_sigungu).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RankChangeResolver {

	public static final String BREAD_BOARD = "BREAD";
	public static final String NATIONWIDE = "";
	static final String WEEKLY_BOARD = "WEEKLY";

	private final RankingSnapshotJdbcRepository snapshotRepository;

	public RankChanges resolve(String board, long categoryId, String region, LocalDate today) {
		// 주간 판은 월요일 0시에 새로 시작한다 — 지난주 등수와 비교하는 건 뜻이 없다
		if (WEEKLY_BOARD.equals(board) && today.getDayOfWeek() == DayOfWeek.MONDAY) {
			return RankChanges.unknown();
		}
		LocalDate yesterday = today.minusDays(1);
		if (!snapshotRepository.existsForDate(yesterday)) {
			return RankChanges.unknown();
		}
		return RankChanges.of(snapshotRepository.findRanks(yesterday, board, categoryId, region));
	}

	public static String regionKey(String sido, String sigungu) {
		return sido == null || sigungu == null ? NATIONWIDE : sido + "_" + sigungu;
	}
}
