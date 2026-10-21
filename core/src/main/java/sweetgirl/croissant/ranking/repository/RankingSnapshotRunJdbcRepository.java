package sweetgirl.croissant.ranking.repository;

import java.time.LocalDate;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 스냅샷 하루 작업의 선점 기록(ranking_snapshot_run). advisory lock 대신 날짜 행 하나로 "오늘은 내가 한다"를 잡는다 —
 * 판마다 트랜잭션을 끊어도 락이 풀리지 않고, 중간에 죽은 실행은 {@link #STALE_AFTER} 뒤에 다른 실행이 이어받는다.
 */
@Repository
@RequiredArgsConstructor
public class RankingSnapshotRunJdbcRepository {

	static final String STALE_AFTER = "1 hour";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/** 그 날짜를 선점한다 — 처음이거나, 1시간 넘게 안 끝난 실행이 있을 때만 true. 끝난 날짜·진행 중인 날짜는 false. */
	public boolean tryStart(LocalDate snapshotDate) {
		int rows = jdbcTemplate.update("""
				INSERT INTO ranking_snapshot_run (snapshot_date, started_at)
				VALUES (:date, NOW())
				ON CONFLICT (snapshot_date) DO UPDATE
					SET started_at = NOW(), finished_at = NULL, saved_rows = NULL, failed_boards = NULL
					WHERE ranking_snapshot_run.finished_at IS NULL
						AND ranking_snapshot_run.started_at < NOW() - INTERVAL '""" + STALE_AFTER + """
				'
				""",
			new MapSqlParameterSource("date", snapshotDate));
		return rows == 1;
	}

	public void finish(LocalDate snapshotDate, int savedRows, int failedBoards) {
		jdbcTemplate.update("""
				UPDATE ranking_snapshot_run
				SET finished_at = NOW(), saved_rows = :savedRows, failed_boards = :failedBoards
				WHERE snapshot_date = :date
				""",
			new MapSqlParameterSource()
				.addValue("date", snapshotDate)
				.addValue("savedRows", savedRows)
				.addValue("failedBoards", failedBoards));
	}
}
