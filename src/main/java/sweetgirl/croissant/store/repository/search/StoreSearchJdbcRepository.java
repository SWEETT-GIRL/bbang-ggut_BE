package sweetgirl.croissant.store.repository.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import sweetgirl.croissant.store.config.StoreSearchProperties;

/**
 * PostgreSQL 검색 SQL 실행. SQL 은 조건에 따라 모양이 바뀌므로 JPA 파생 쿼리 대신 NamedParameterJdbcTemplate 로 실행한다.
 * 문장은 {@code StoreSearchSqlBuilder} 가 만들고, 여기서는 실행과 행 매핑만 한다.
 */
@Repository
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
				rs.getDouble("pop"),
				nullableDouble(rs, "center_lat"),
				nullableDouble(rs, "center_lng"),
				nullableInteger(rs, "radius_meters"),
				rs.getString("parent_name"));

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * 검색 전용 템플릿을 따로 만들어 statement 타임아웃을 건다. 자동설정 템플릿(적재·랭킹 등 다른 레포지토리가 공유)에 걸면
	 * 배치까지 2초에 끊기므로 여기서만 쓴다. 빈으로 등록하지 않으니 Spring Boot 의 NamedParameterJdbcTemplate 자동설정도 그대로 산다.
	 */
	@Autowired
	public StoreSearchJdbcRepository(DataSource dataSource, StoreSearchProperties properties) {
		JdbcTemplate timedTemplate = new JdbcTemplate(dataSource);
		timedTemplate.setQueryTimeout(properties.getQueryTimeoutSeconds());
		this.jdbcTemplate = new NamedParameterJdbcTemplate(timedTemplate);
	}

	/** 랩 테스트용 — 준 템플릿을 그대로 쓴다(타임아웃 없음). */
	public StoreSearchJdbcRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 실제로 걸린 statement 타임아웃(초). -1 이면 드라이버 기본값(무제한). */
	int queryTimeoutSeconds() {
		return jdbcTemplate.getJdbcTemplate().getQueryTimeout();
	}

	public List<StoreSearchRow> search(SearchSqlQuery query) {
		return jdbcTemplate.query(query.sql(), query.params(), SEARCH_ROW_MAPPER);
	}

	public List<StoreAutocompleteRow> autocomplete(SearchSqlQuery query) {
		return jdbcTemplate.query(query.sql(), query.params(), AUTOCOMPLETE_ROW_MAPPER);
	}

	private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}
}
