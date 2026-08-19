package sweetgirl.croissant.store.repository.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.service.search.StoreSearchSqlBuilder.SqlQuery;

/**
 * PostgreSQL 검색 SQL 실행. SQL 은 조건에 따라 모양이 바뀌므로 JPA 파생 쿼리 대신 NamedParameterJdbcTemplate 로 실행한다.
 * 문장은 {@code StoreSearchSqlBuilder} 가 만들고, 여기서는 실행과 행 매핑만 한다.
 */
@Repository
@RequiredArgsConstructor
public class StoreSearchJdbcRepository {

	private static final RowMapper<StoreSearchRow> SEARCH_ROW_MAPPER = (rs, rowNum) -> new StoreSearchRow(
		rs.getLong("id"),
		rs.getDouble("score"),
		rs.getDouble("popularity_score"),
		rs.getDouble("name_score"),
		rs.getDouble("alias_score"),
		rs.getDouble("region_score"),
		rs.getDouble("address_score"),
		nullableDouble(rs, "distance_m"));

	private static final RowMapper<StoreAutocompleteRow> AUTOCOMPLETE_ROW_MAPPER = (rs,
			rowNum) -> new StoreAutocompleteRow(
				rs.getString("keyword"),
				rs.getString("type"),
				nullableLong(rs, "store_id"),
				rs.getInt("tier"),
				rs.getDouble("pop"));

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public List<StoreSearchRow> search(SqlQuery query) {
		return jdbcTemplate.query(query.sql(), query.params(), SEARCH_ROW_MAPPER);
	}

	public List<StoreAutocompleteRow> autocomplete(SqlQuery query) {
		return jdbcTemplate.query(query.sql(), query.params(), AUTOCOMPLETE_ROW_MAPPER);
	}

	private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}
}
