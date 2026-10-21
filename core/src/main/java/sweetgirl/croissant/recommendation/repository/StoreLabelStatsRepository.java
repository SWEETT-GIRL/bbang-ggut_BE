package sweetgirl.croissant.recommendation.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.personalization.entity.TasteLabelVector;

/**
 * 가게 라벨 표의 집계 — 지금은 중심화 상수(아홉 값의 평균)만 읽는다.
 *
 * <p>평균을 내는 대상은 <b>표 전체</b>다. 후보 자격(빵집다움·상권)으로 거르지 않는다 — 중심은 "가게 일반이 어느
 * 정도인가" 여야 하고, 후보 집합마다 중심이 달라지면 같은 가게의 점수가 어느 상권에서 뽑혔느냐에 따라 달라진다.
 */
@Repository
@RequiredArgsConstructor
public class StoreLabelStatsRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * 아홉 값의 평균을 {@link TasteLabelVector#values()} 와 같은 순서로. 표가 비어 있으면 null.
	 *
	 * <p>순서가 어긋나면 달콤의 중심이 담백에 적용되는 식이 된다 — 예외도 안 나고 추천만 조용히 틀린다.
	 */
	public double[] findLabelMeans() {
		return jdbcTemplate.query("""
				SELECT count(*) AS rows,
					avg(taste_sweet) AS taste_sweet, avg(taste_plain) AS taste_plain,
					avg(taste_salty) AS taste_salty, avg(taste_aroma) AS taste_aroma,
					avg(tex_soft) AS tex_soft, avg(tex_crisp) AS tex_crisp, avg(tex_chewy) AS tex_chewy,
					avg(filling_rich) AS filling_rich, avg(vibe_trendy) AS vibe_trendy
				FROM store_recommendation_feature
				""", Map.of(), StoreLabelStatsRepository::readMeans);
	}

	private static double[] readMeans(ResultSet rs) throws SQLException {
		if (!rs.next() || rs.getLong("rows") == 0) {
			return null;
		}
		return new double[] {
			rs.getDouble("taste_sweet"), rs.getDouble("taste_plain"), rs.getDouble("taste_salty"),
			rs.getDouble("taste_aroma"), rs.getDouble("tex_soft"), rs.getDouble("tex_crisp"),
			rs.getDouble("tex_chewy"), rs.getDouble("filling_rich"), rs.getDouble("vibe_trendy")};
	}
}
