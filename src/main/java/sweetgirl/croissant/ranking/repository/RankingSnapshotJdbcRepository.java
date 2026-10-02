package sweetgirl.croissant.ranking.repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;

/**
 * 순위 스냅샷(ranking_snapshot) — 하루 한 번 판마다 쓰고, 조회 때 어제 것을 읽는다. 행이 많고 모양이 단순해 JDBC 로 둔다.
 * 키 = (날짜, 판, 카테고리(사람 판은 0), 지역('' = 전국), 대상 id).
 */
@Repository
@RequiredArgsConstructor
public class RankingSnapshotJdbcRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/** 한 판의 그날 것을 지운다 — 판 하나를 다시 쓰기 전에. */
	public int deleteBoard(LocalDate snapshotDate, String board, long categoryId, String region) {
		return jdbcTemplate.update("""
				DELETE FROM ranking_snapshot
				WHERE snapshot_date = :date AND board = :board AND category_id = :categoryId AND region = :region
				""",
			new MapSqlParameterSource()
				.addValue("date", snapshotDate)
				.addValue("board", board)
				.addValue("categoryId", categoryId)
				.addValue("region", region));
	}

	public int deleteBefore(LocalDate exclusive) {
		return jdbcTemplate.update("DELETE FROM ranking_snapshot WHERE snapshot_date < :date",
			new MapSqlParameterSource("date", exclusive));
	}

	/** 한 판의 상위 N — 같은 키가 없다는 전제({@link #deleteBoard} 뒤에). */
	public int insertAll(LocalDate snapshotDate, String board, long categoryId, String region,
			List<RankedSubject> rows) {
		if (rows.isEmpty()) {
			return 0;
		}
		SqlParameterSource[] batch = rows.stream()
			.map(row -> new MapSqlParameterSource()
				.addValue("date", snapshotDate)
				.addValue("board", board)
				.addValue("categoryId", categoryId)
				.addValue("region", region)
				.addValue("subjectId", row.subjectId())
				.addValue("rank", row.rank()))
			.toArray(SqlParameterSource[]::new);
		jdbcTemplate.batchUpdate("""
				INSERT INTO ranking_snapshot (snapshot_date, board, category_id, region, subject_id, rank)
				VALUES (:date, :board, :categoryId, :region, :subjectId, :rank)
				""", batch);
		return rows.size();
	}

	/** 그날 스냅샷이 하나라도 있는가 — 없으면 "첫날"이라 변동을 안 보여 준다. */
	public boolean existsForDate(LocalDate snapshotDate) {
		Integer found = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM (SELECT 1 FROM ranking_snapshot WHERE snapshot_date = :date LIMIT 1) t",
			new MapSqlParameterSource("date", snapshotDate), Integer.class);
		return found != null && found > 0;
	}

	/** 한 판의 그날 등수 — 대상 id → 등수. */
	public Map<Long, Integer> findRanks(LocalDate snapshotDate, String board, long categoryId, String region) {
		Map<Long, Integer> ranks = new LinkedHashMap<>();
		jdbcTemplate.query("""
				SELECT subject_id, rank
				FROM ranking_snapshot
				WHERE snapshot_date = :date AND board = :board AND category_id = :categoryId AND region = :region
				""",
			new MapSqlParameterSource()
				.addValue("date", snapshotDate)
				.addValue("board", board)
				.addValue("categoryId", categoryId)
				.addValue("region", region),
			resultSet -> {
				ranks.put(resultSet.getLong("subject_id"), resultSet.getInt("rank"));
			});
		return ranks;
	}
}
