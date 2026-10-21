package sweetgirl.croissant.ranking.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.ranking.repository.RankingSnapshotRunJdbcRepository;
import sweetgirl.croissant.ranking.snapshot.RankingSnapshotSource;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

/**
 * 순위 스냅샷 — 매일 00:05 KST 에 "어제 마감" 상위 100 을 판마다 남긴다. 조회는 {@link RankChangeResolver} 가 어제 것을 읽는다.
 *
 * <p>여기엔 트랜잭션이 없다. 날짜 행으로 하루 작업을 선점한 뒤({@link RankingSnapshotRunJdbcRepository}) 판 하나당
 * 짧은 트랜잭션({@link RankingSnapshotWriter})으로 쓴다 — 구가 수백 개가 돼도 한 트랜잭션이 길어지지 않고,
 * 중간에 죽어도 끝난 판은 남고 다음 실행이 나머지만 채운다. 어느 판을 남길지는 {@link RankingSnapshotSource} 들이 정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingSnapshotCommandService {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	static final int RETENTION_DAYS = 14;

	private final RankingSnapshotRunJdbcRepository runRepository;
	private final RankingSnapshotWriter writer;
	private final List<RankingSnapshotSource> sources;

	/** @return 저장한 줄 수. 그 날짜를 다른 실행이 이미 끝냈거나 진행 중이면 -1 */
	public int snapshotYesterday() {
		return snapshot(LocalDate.now(ZONE));
	}

	int snapshot(LocalDate today) {
		LocalDate snapshotDate = today.minusDays(1);
		if (!runRepository.tryStart(snapshotDate)) {
			log.info("[랭킹 스냅샷] date={} 는 이미 끝났거나 다른 실행이 진행 중 — 건너뜀", snapshotDate);
			return -1;
		}
		int saved = 0;
		int failed = 0;
		for (RankingSnapshotSource source : sources) {
			for (SnapshotBoard board : source.boards(snapshotDate)) {
				try {
					saved += writer.replace(snapshotDate, board);
				} catch (RuntimeException exception) {
					// 한 판이 깨져도 나머지 판은 남긴다 — 깨진 판만 그날 변동이 안 보인다
					failed++;
					log.error("[랭킹 스냅샷] 판 저장 실패 board={} category={} region={}",
						board.board(), board.categoryId(), board.region(), exception);
				}
			}
		}
		int purged = writer.purgeBefore(today.minusDays(RETENTION_DAYS));
		runRepository.finish(snapshotDate, saved, failed);
		log.info("[랭킹 스냅샷] date={} saved={} failedBoards={} purged={}", snapshotDate, saved, failed, purged);
		return saved;
	}
}
