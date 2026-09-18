package sweetgirl.croissant.store.repository.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import sweetgirl.croissant.store.config.StoreSearchProperties;
import sweetgirl.croissant.store.service.search.SearchSort;
import sweetgirl.croissant.store.service.search.StoreSearchPlan;
import sweetgirl.croissant.store.service.search.StoreSearchSqlBuilder;

/**
 * 계측용 랩 테스트: 인기도 산식이 바뀐 뒤(V20260918180000 적용) 검색 관련도 계수 0.3 과 0.68 의 상위 결과를 나란히 찍는다(#663 ③).
 * 단언은 "두 계수 모두 결과가 있고 점수 내림차순" 뿐이고, 표는 PR 본문의 근거다.
 */
@Tag("lab")
class StoreSearchPopularityFactorLabMeasurementTest {

	private static final double OLD_FACTOR = 0.3;
	private static final double NEW_FACTOR = 0.68;
	private static final List<String> QUERIES = List.of("성심당", "베이글", "소금빵", "도넛", "빵");

	private static StoreSearchJdbcRepository repository;
	private static JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void connect() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			System.getProperty("lab.db.url", "jdbc:postgresql://localhost:5432/soboro"),
			System.getProperty("lab.db.user", "sweetgirl"),
			System.getProperty("lab.db.password", "soboro-lab"));
		repository = new StoreSearchJdbcRepository(new NamedParameterJdbcTemplate(dataSource));
		jdbcTemplate = new JdbcTemplate(dataSource);
	}

	private static StoreSearchSqlBuilder builder(double factor) {
		StoreSearchProperties properties = new StoreSearchProperties();
		properties.setPopularityFactor(factor);
		return new StoreSearchSqlBuilder(properties);
	}

	private static String describe(List<StoreSearchRow> rows) {
		StringBuilder line = new StringBuilder();
		for (StoreSearchRow row : rows) {
			String name = jdbcTemplate.queryForObject("SELECT name FROM store WHERE id = ?", String.class,
				row.storeId());
			line.append(String.format("%s(%.0f·인기 %.1f) ", name, row.score(), row.popularityScore()));
		}
		return line.toString();
	}

	@Test
	@DisplayName("계수 0.3 vs 0.68 — 이름 검색 상위 5 비교표")
	void compareFactors() {
		StringBuilder report = new StringBuilder("[계측] 검색 관련도 계수 비교 (상위 5)\n");
		for (String query : QUERIES) {
			StoreSearchPlan plan = new StoreSearchPlan(List.of(), List.of(query), query, false);
			List<StoreSearchRow> oldRows = repository.search(
				builder(OLD_FACTOR).buildSearch(plan, 5, 0, null, null, SearchSort.RELEVANCE));
			List<StoreSearchRow> newRows = repository.search(
				builder(NEW_FACTOR).buildSearch(plan, 5, 0, null, null, SearchSort.RELEVANCE));
			assertThat(oldRows).isNotEmpty();
			assertThat(newRows).isNotEmpty();
			for (int i = 1; i < newRows.size(); i++) {
				assertThat(newRows.get(i).score()).isLessThanOrEqualTo(newRows.get(i - 1).score());
			}
			report.append(String.format("[계측] %s | 0.3: %s%n[계측] %s | 0.68: %s%n", query, describe(oldRows), query,
				describe(newRows)));
		}
		System.out.println(report);
	}
}
