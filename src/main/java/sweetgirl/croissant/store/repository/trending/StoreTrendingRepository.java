package sweetgirl.croissant.store.repository.trending;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 급부상 판정용 DB 접근. 평소 수준(store.trending_baseline) 갱신과, 순위표를 만들 때 필요한 평소 수준·이름 조회.
 *
 * <p>평소 수준 = (최근 창 바로 앞 기간 동안의 신호 가중합) ÷ 일수. 신호는 user_store_interaction 에서 회원×가게×종류×일 단위로 센다 —
 * 실시간 쪽(Redis)도 같은 회원·가게·행동을 하루 한 번만 세므로 둘을 나눈 배율이 의미를 갖는다.
 */
@Repository
@RequiredArgsConstructor
public class StoreTrendingRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/** 같은 트랜잭션 안에서 UPDATE 앞에 부른다 — 배치와 어드민 수동 갱신이 겹치지 않게 한다 */
	public boolean tryLock(long lockKey) {
		Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(:key)",
			new MapSqlParameterSource("key", lockKey), Boolean.class);
		return Boolean.TRUE.equals(locked);
	}

	/**
	 * @param weights user_store_interaction.type → 가중치. 0 이하는 넣지 않는다
	 * @param from    기간 시작(포함)
	 * @param to      기간 끝(제외)
	 * @param days    일평균을 낼 일수
	 * @return 값이 바뀐 가게 수
	 */
	public int refreshBaselines(Map<String, Double> weights, LocalDateTime from, LocalDateTime to, long days) {
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("from", from)
			.addValue("to", to)
			.addValue("days", (double)days);
		List<String> weightRows = new ArrayList<>();
		int index = 0;
		for (Map.Entry<String, Double> weight : weights.entrySet()) {
			weightRows.add("(CAST(:type" + index + " AS varchar), CAST(:weight" + index + " AS float8))");
			params.addValue("type" + index, weight.getKey()).addValue("weight" + index, weight.getValue());
			index++;
		}
		String sql = """
				WITH weight(type, value) AS (VALUES %s),
				signal AS (
					SELECT i.store_id, i.type, COUNT(DISTINCT (i.member_id, CAST(i.occurred_at AS date))) AS member_days
					FROM user_store_interaction i
					WHERE i.store_id IS NOT NULL AND i.occurred_at >= :from AND i.occurred_at < :to
						AND i.type IN (SELECT type FROM weight)
					GROUP BY i.store_id, i.type
				),
				scored AS (
					SELECT s.store_id, SUM(s.member_days * w.value) / :days AS baseline
					FROM signal s JOIN weight w ON w.type = s.type
					GROUP BY s.store_id
				)
				UPDATE store target SET trending_baseline = COALESCE(sc.baseline, 0)
				FROM store base LEFT JOIN scored sc ON sc.store_id = base.id
				WHERE target.id = base.id AND target.trending_baseline IS DISTINCT FROM COALESCE(sc.baseline, 0)
				""".formatted(String.join(", ", weightRows));
		return jdbcTemplate.update(sql, params);
	}

	public Map<Long, Double> findBaselines(Collection<Long> storeIds) {
		Map<Long, Double> baselines = new HashMap<>();
		if (storeIds.isEmpty()) {
			return baselines;
		}
		jdbcTemplate.query("SELECT id, trending_baseline FROM store WHERE id IN (:ids)",
			new MapSqlParameterSource("ids", storeIds),
			resultSet -> {
				baselines.put(resultSet.getLong("id"), resultSet.getDouble("trending_baseline"));
			});
		return baselines;
	}

	/** 노출 중인 가게만 — 숨긴 가게를 자동완성 맨 위로 올리지 않는다 */
	public List<TrendingStoreName> findActiveNames(Collection<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return List.of();
		}
		String sql = """
				SELECT s.id, s.name, s.name_normalized,
					ARRAY_REMOVE(ARRAY_AGG(a.alias_normalized), NULL) AS aliases
				FROM store s LEFT JOIN store_name_alias a ON a.store_id = s.id
				WHERE s.id IN (:ids) AND s.status = 'ACTIVE'
				GROUP BY s.id, s.name, s.name_normalized
				""";
		return jdbcTemplate.query(sql, new MapSqlParameterSource("ids", storeIds), (resultSet, rowNum) -> {
			List<String> keys = new ArrayList<>();
			keys.add(resultSet.getString("name_normalized"));
			keys.addAll(strings(resultSet.getArray("aliases")));
			return new TrendingStoreName(resultSet.getLong("id"), resultSet.getString("name"), keys);
		});
	}

	private static List<String> strings(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		return Arrays.stream((Object[])array.getArray()).map(String::valueOf).toList();
	}
}
