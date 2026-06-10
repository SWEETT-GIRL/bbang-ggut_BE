package sweetgirl.croissant.external.opensearch.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import sweetgirl.croissant.store.service.search.SearchIntent;

class StoreSearchQueryBuilderTest {

	private final StoreSearchQueryBuilder builder = new StoreSearchQueryBuilder();

	@Test
	void build_usesRegionAndBreadAsMustWhenBothExist() {
		SearchIntent intent = intent("성수 에그타르트", List.of("성수"), List.of("에그타르트"));

		Map<String, Object> query = builder.build(intent, PageRequest.of(0, 15), null, null, false);

		Map<String, Object> bool = boolQuery(query);
		List<Object> must = list(bool.get("must"));
		List<Object> should = list(bool.get("should"));
		assertThat(must).hasSize(2);
		assertThat(should).hasSize(1);
		assertThat(query.toString()).contains(
			"searchRegions.name",
			"searchRegionAliases",
			"breadTypes",
			"menus.menuCategory",
			"menus.name",
			"matchedMenus0");
	}

	@Test
	void build_usesRawQueryForStoreNameAndMenuName() {
		SearchIntent intent = intent("뺑스톡", List.of(), List.of());

		Map<String, Object> query = builder.build(intent, PageRequest.of(1, 10), null, null, false);

		Map<String, Object> bool = boolQuery(query);
		assertThat(query.get("from")).isEqualTo(10L);
		assertThat(query.get("size")).isEqualTo(10);
		assertThat(bool.get("minimum_should_match")).isEqualTo(1);
		assertThat(query.toString()).contains(
			"storeName",
			"menus.name",
			"rawMatchedMenus");
	}

	@Test
	void build_reflectsPopularityScoreWithFunctionScore() {
		SearchIntent intent = intent("소금빵", List.of(), List.of("소금빵"));

		Map<String, Object> query = builder.build(intent, PageRequest.of(0, 15), null, null, false);

		Map<String, Object> functionScore = map(map(query.get("query")).get("function_score"));
		assertThat(functionScore.get("boost_mode")).isEqualTo("sum");
		assertThat(functionScore.get("score_mode")).isEqualTo("sum");
		assertThat(functionScore.toString()).contains(
			"field_value_factor",
			"popularityScore",
			"sqrt");
	}

	@Test
	void build_addsDistanceFunctionAndSortWhenLocationExistsAndDistanceSortRequested() {
		SearchIntent intent = intent("한남 소금빵", List.of("한남"), List.of("소금빵"));

		Map<String, Object> query = builder.build(intent, PageRequest.of(0, 15), 37.5, 127.0, true);

		Map<String, Object> functionScore = map(map(query.get("query")).get("function_score"));
		assertThat(functionScore.toString()).contains(
			"gauss",
			"location",
			"37.5",
			"127.0");
		assertThat(list(query.get("sort")).toString()).contains(
			"_geo_distance",
			"order=asc",
			"unit=m");
	}

	private SearchIntent intent(String rawQuery, List<String> regionTokens, List<String> breadTokens) {
		return new SearchIntent(rawQuery, List.of(rawQuery.split(" ")), regionTokens, breadTokens, breadTokens);
	}

	private Map<String, Object> boolQuery(Map<String, Object> query) {
		Map<String, Object> functionScore = map(map(query.get("query")).get("function_score"));
		return map(map(functionScore.get("query")).get("bool"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (Map<String, Object>)value;
	}

	@SuppressWarnings("unchecked")
	private List<Object> list(Object value) {
		return (List<Object>)value;
	}
}
