package sweetgirl.croissant.recommendation.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 추천 후보 SQL (설계 §5.3 · §5.6). PostGIS 반경 조회라 H2 로는 못 돌린다 — 검증은 랩 테스트(@Tag lab).
 *
 * 점수 계산은 인라인 SQL 로 둔다 — PL/pgSQL 함수로 빼면 행마다 호출 비용이 붙는다(스키마 노션 §8.1).
 * 지금(#660)은 store_profile 이 비어 있어 취향 항이 없다: 정렬은 인기 점수(store.popularity_score)뿐이고,
 * 축 코사인·태그·종류 겹침은 #661 이 같은 문장에 더한다. 코스에 이미 담긴 가게는 호출자가 excludeStoreIds 로 뺀다.
 */
@Repository
@RequiredArgsConstructor
public class RecommendationCandidateJdbcRepository {

	private static final String CENTER = "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography";

	private static final String SELECT_STORE = """
			SELECT s.id, s.name, s.region_3rd,
				ST_Y(s.location::geometry) AS latitude, ST_X(s.location::geometry) AS longitude,
				ST_Distance(s.location, %1$s) AS distance_m,
				s.popularity_score,
				img.thumbnail_key
			FROM store s
			LEFT JOIN LATERAL (
				SELECT COALESCE(f.resized_key_320, f.aws_key) AS thumbnail_key
				FROM store_image_file f
				WHERE f.store_id = s.id AND (f.resized_key_320 IS NOT NULL OR f.aws_key IS NOT NULL)
				ORDER BY f.display_order ASC, f.id ASC
				LIMIT 1
			) img ON TRUE
			WHERE s.status = 'ACTIVE'
				AND s.id <> ALL (:excludeStoreIds)
			""".formatted(CENTER);

	private static final RowMapper<CandidateStoreRow> ROW_MAPPER = (rs, rowNum) -> new CandidateStoreRow(
		rs.getLong("id"),
		rs.getString("name"),
		rs.getString("region_3rd"),
		rs.getDouble("latitude"),
		rs.getDouble("longitude"),
		nullableString(rs, "thumbnail_key"),
		rs.getDouble("distance_m"),
		rs.getDouble("popularity_score"));

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * 상권 후보: 반경 안이거나(주 규칙) 시군구·동이 맞는(보조) ACTIVE 가게를 인기순으로. region2nd 가 null 이면 반경만 본다.
	 */
	public List<CandidateStoreRow> findAreaCandidates(
			double centerLat, double centerLng, int radiusM, String region2nd, String region3rd,
			Collection<Long> excludeStoreIds, int limit) {
		StringBuilder sql = new StringBuilder(SELECT_STORE)
			.append("\tAND (ST_DWithin(s.location, ").append(CENTER).append(", :radiusM)");
		MapSqlParameterSource params = centerParams(centerLat, centerLng, excludeStoreIds)
			.addValue("radiusM", radiusM)
			.addValue("limit", limit);
		if (region2nd != null) {
			sql.append(" OR (s.region_2nd = :region2nd");
			params.addValue("region2nd", region2nd);
			if (region3rd != null) {
				sql.append(" AND s.region_3rd = :region3rd");
				params.addValue("region3rd", region3rd);
			}
			sql.append(")");
		}
		sql.append(")\n\tORDER BY s.popularity_score DESC, s.id ASC\n\tLIMIT :limit");
		return jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
	}

	/**
	 * 앵커 후보: 앵커 좌표에서 maxRadiusM 안 ACTIVE 가게를 ringM 너비의 링 안쪽부터, 링 안에서는 인기순으로.
	 * 한 번에 상한까지 읽고 호출자가 페이지로 자른다 — 반경 조회 비용은 반경에 거의 비례하지 않는다(운영 실측 1.2km 11ms · 5km 15ms).
	 */
	public List<CandidateStoreRow> findAnchorCandidates(
			double anchorLat, double anchorLng, int ringM, int maxRadiusM, Collection<Long> excludeStoreIds,
			int limit) {
		String sql = SELECT_STORE
			+ "\tAND ST_DWithin(s.location, " + CENTER + ", :maxRadiusM)\n"
			+ "\tORDER BY CEIL(ST_Distance(s.location, " + CENTER
			+ ") / :ringM) ASC, s.popularity_score DESC, s.id ASC\n"
			+ "\tLIMIT :limit";
		MapSqlParameterSource params = centerParams(anchorLat, anchorLng, excludeStoreIds)
			.addValue("maxRadiusM", maxRadiusM)
			.addValue("ringM", ringM)
			.addValue("limit", limit);
		return jdbcTemplate.query(sql, params, ROW_MAPPER);
	}

	/** 상권 칩의 storeCount — 반경 안 ACTIVE 가게 수. */
	public int countActiveStoresWithin(double centerLat, double centerLng, int radiusM) {
		String sql = "SELECT COUNT(*) FROM store s WHERE s.status = 'ACTIVE' AND ST_DWithin(s.location, " + CENTER
			+ ", :radiusM)";
		Integer count = jdbcTemplate.queryForObject(sql,
			centerParams(centerLat, centerLng, List.of()).addValue("radiusM", radiusM), Integer.class);
		return count != null ? count : 0;
	}

	/** 칩 근거 SAVED — 이 회원이 저장한 가게가 반경 안에 있는지. */
	public boolean existsSavedStoreWithin(Long memberId, double centerLat, double centerLng, int radiusM) {
		String sql = "SELECT EXISTS (SELECT 1 FROM saved_stores ss JOIN store s ON s.id = ss.store_id"
			+ " WHERE ss.member_id = :memberId AND ST_DWithin(s.location, " + CENTER + ", :radiusM))";
		Boolean exists = jdbcTemplate.queryForObject(sql,
			centerParams(centerLat, centerLng, List.of()).addValue("radiusM", radiusM).addValue("memberId", memberId),
			Boolean.class);
		return Boolean.TRUE.equals(exists);
	}

	/** <> ALL(:excludeStoreIds) 은 빈 배열에도 참이라 제외 목록이 없을 때 조건을 지울 필요가 없다. */
	private static MapSqlParameterSource centerParams(double lat, double lng, Collection<Long> excludeStoreIds) {
		return new MapSqlParameterSource()
			.addValue("lat", lat)
			.addValue("lng", lng)
			.addValue("excludeStoreIds", excludeStoreIds.toArray(new Long[0]));
	}

	private static String nullableString(ResultSet rs, String column) throws SQLException {
		String value = rs.getString(column);
		return rs.wasNull() ? null : value;
	}
}
