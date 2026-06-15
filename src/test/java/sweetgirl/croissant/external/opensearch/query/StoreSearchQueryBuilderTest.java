package sweetgirl.croissant.external.opensearch.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.FunctionBoostMode;
import org.opensearch.client.opensearch._types.query_dsl.FunctionScoreMode;
import org.opensearch.client.opensearch._types.query_dsl.FunctionScoreQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.springframework.data.domain.PageRequest;

import jakarta.json.stream.JsonGenerator;
import sweetgirl.croissant.external.opensearch.config.OpenSearchProperties;
import sweetgirl.croissant.store.service.search.SearchIntent;
import sweetgirl.croissant.store.service.search.SearchSort;

class StoreSearchQueryBuilderTest {

	private static final JacksonJsonpMapper MAPPER = new JacksonJsonpMapper();

	private final StoreSearchQueryBuilder builder = new StoreSearchQueryBuilder(new OpenSearchProperties());

	@Test
	void build_usesRegionAndBreadAsMustWhenBothExist() {
		SearchIntent intent = intent("성수 에그타르트", List.of("성수"), List.of("에그타르트"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).hasSize(2);
		assertThat(bool.should()).hasSize(1);
		assertThat(toJson(request)).contains(
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

		SearchRequest request = builder.build(intent, PageRequest.of(1, 10), null, null, SearchSort.RELEVANCE);

		assertThat(request.from()).isEqualTo(10);
		assertThat(request.size()).isEqualTo(10);
		assertThat(boolQuery(request).minimumShouldMatch()).isEqualTo("1");
		assertThat(toJson(request)).contains(
			"storeName",
			"menus.name",
			"rawMatchedMenus");
	}

	@Test
	void build_requiresBreadMatchAndKeepsRawAsShouldWhenBreadOnly() {
		SearchIntent intent = intent("자연도", List.of(), List.of("자연도"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		// 케이스 3: 빵 키워드만 있어도 빵 매칭은 must(필수), 원문 매칭만 should로 점수 보정
		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).hasSize(1);
		assertThat(bool.should()).hasSize(1);
		assertThat(bool.minimumShouldMatch()).isNull();
		assertThat(toJson(request)).contains(
			"storeName",
			"breadTypes",
			"menus.name");
	}

	@Test
	void build_reflectsPopularityScoreWithFunctionScore() {
		SearchIntent intent = intent("소금빵", List.of(), List.of("소금빵"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		FunctionScoreQuery functionScore = request.query().functionScore();
		assertThat(functionScore.boostMode()).isEqualTo(FunctionBoostMode.Sum);
		assertThat(functionScore.scoreMode()).isEqualTo(FunctionScoreMode.Sum);
		assertThat(toJson(request)).contains(
			"field_value_factor",
			"popularityScore",
			"sqrt");
	}

	@Test
	void build_addsDistanceFunctionAndSortWhenDistanceSortRequested() {
		SearchIntent intent = intent("한남 소금빵", List.of("한남"), List.of("소금빵"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), 37.5, 127.0, SearchSort.DISTANCE);

		assertThat(toJson(request)).contains(
			"gauss",
			"location",
			"37.5",
			"127.0",
			"_geo_distance");
		assertThat(request.sort()).hasSize(2);
		assertThat(request.sort().get(0).isGeoDistance()).isTrue();
	}

	@Test
	void build_addsPopularitySortWhenPopularitySortRequested() {
		SearchIntent intent = intent("에그타르트", List.of(), List.of("에그타르트"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), 37.5, 127.0, SearchSort.POPULARITY);

		assertThat(request.sort()).hasSize(2);
		assertThat(request.sort().get(0).field().field()).isEqualTo("popularityScore");
		assertThat(toJson(request)).contains("popularityScore", "_score");
	}

	@Test
	void build_omitsExplicitSortWhenRelevanceSortRequested() {
		SearchIntent intent = intent("뺑스톡", List.of(), List.of());

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), 37.5, 127.0, SearchSort.RELEVANCE);

		assertThat(request.sort()).isEmpty();
	}

	@Test
	void buildAutocomplete_usesAutocompleteFieldsAndPopularityScore() {
		SearchRequest request = builder.buildAutocomplete("소금", 10);

		assertThat(request.size()).isEqualTo(10);
		assertThat(toJson(request)).contains(
			"storeName.edge",
			"breadTypes.edge",
			"menus.name.edge",
			"searchRegionAliases.edge",
			"searchRegions.name.edge",
			"popularityScore");
	}

	private SearchIntent intent(String rawQuery, List<String> regionTokens, List<String> breadTokens) {
		return new SearchIntent(rawQuery, rawQuery, List.of(rawQuery.split(" ")), regionTokens, breadTokens,
			breadTokens);
	}

	private BoolQuery boolQuery(SearchRequest request) {
		return request.query().functionScore().query().bool();
	}

	private String toJson(SearchRequest request) {
		StringWriter writer = new StringWriter();
		try (JsonGenerator generator = MAPPER.jsonProvider().createGenerator(writer)) {
			request.serialize(generator, MAPPER);
		}
		return writer.toString();
	}
}
