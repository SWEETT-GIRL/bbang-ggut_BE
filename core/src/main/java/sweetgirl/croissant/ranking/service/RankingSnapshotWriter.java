package sweetgirl.croissant.ranking.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.ranking.repository.RankingSnapshotJdbcRepository;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

/**
 * 판 하나 = 트랜잭션 하나. 줄을 읽고(판의 Supplier) 그날 것을 지운 뒤 다시 넣는다 — 한 판이 실패해도 다른 판은 남는다.
 * 별도 빈인 이유: 오케스트레이터가 같은 클래스 안에서 부르면 REQUIRES_NEW 가 안 먹는다(프록시).
 */
@Service
@RequiredArgsConstructor
public class RankingSnapshotWriter {

	private final RankingSnapshotJdbcRepository snapshotRepository;

	/** @return 저장한 줄 수 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int replace(LocalDate snapshotDate, SnapshotBoard board) {
		List<RankedSubject> rows = board.rows().get();
		snapshotRepository.deleteBoard(snapshotDate, board.board(), board.categoryId(), board.region());
		return snapshotRepository.insertAll(snapshotDate, board.board(), board.categoryId(), board.region(), rows);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int purgeBefore(LocalDate exclusive) {
		return snapshotRepository.deleteBefore(exclusive);
	}
}
