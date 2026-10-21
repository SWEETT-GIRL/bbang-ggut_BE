package sweetgirl.croissant.ranking.snapshot;

import java.time.LocalDate;
import java.util.List;

/**
 * 스냅샷을 남길 판을 내놓는 쪽 — 빵순이 판(breadlog)과 빵집 판(breadranking)이 각각 구현한다.
 * 스냅샷 모듈(ranking)은 도메인을 모르고, 도메인이 이쪽에 기댄다.
 */
public interface RankingSnapshotSource {

	/** 그날 남길 판 목록. 줄 읽기는 미뤄 둔다({@link SnapshotBoard#rows()}). */
	List<SnapshotBoard> boards(LocalDate snapshotDate);
}
