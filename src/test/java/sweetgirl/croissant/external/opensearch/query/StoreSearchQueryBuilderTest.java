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
			"storeName.keyword",
			"storeName.edge",
			"storeName.ngram",
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
	void build_addsRemainingTokenAsMustWhenBreadPresent() {
		// 폴백 시나리오: "초코소금빵" → bread "소금빵"(must) + remaining "초코"(must, 가게명/메뉴 매칭)
		SearchIntent intent = intent("초코소금빵", List.of(), List.of("소금빵"), List.of("초코"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).hasSize(2); // bread("소금빵") + remaining("초코")
		assertThat(bool.should()).hasSize(1); // rawQuery(원문)
		assertThat(bool.minimumShouldMatch()).isNull();
		assertThat(toJson(request)).contains("remainingMatchedMenus0");
	}

	@Test
	void build_usesRegionAndRemainingAsMustWhenBothExist() {
		// "망원 브릭베" → region("망원")과 remaining("브릭베") 모두 must.
		// "브릭베"가 가게명/메뉴에 매칭되는 망원 가게만 노출(망원 전체 flood 제거).
		SearchIntent intent = intent("망원 브릭베", List.of("망원"), List.of(), List.of("브릭베"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).hasSize(2); // region("망원") + remaining("브릭베")
		assertThat(bool.should()).hasSize(1); // rawQuery
		assertThat(bool.minimumShouldMatch()).isNull();
		assertThat(toJson(request)).contains("remainingMatchedMenus0", "searchRegions.name", "searchRegionAliases");
	}

	@Test
	void build_usesRemainingAsMustWhenRemainingOnly() {
		// 지역 없는 가게명 지목 "테디뵈르하우스" → 잔여를 must로 걸어 전국에서 그 이름/메뉴 가진 가게만
		SearchIntent intent = intent("테디뵈르하우스", List.of(), List.of(), List.of("테디뵈르하우스"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).hasSize(1); // remaining("테디뵈르하우스")
		assertThat(bool.should()).hasSize(1); // rawQuery
		assertThat(bool.minimumShouldMatch()).isNull();
		assertThat(toJson(request)).contains("remainingMatchedMenus0");
	}

	@Test
	void build_keepsUsableRemainingButDropsShortNoiseInMust() {
		// "하츠베이커리 성수점" → region "성수", remaining ["하츠베이커리"(유효), "점"(길이1 노이즈)].
		// "하츠베이커리"만 must에 남고 "점"은 제외 → 성수의 하츠베이커리만.
		SearchIntent intent = intent("하츠베이커리 성수점", List.of("성수"), List.of(),
			List.of("하츠베이커리", "점"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).hasSize(2); // region("성수") + remaining("하츠베이커리")
		assertThat(bool.should()).hasSize(1); // rawQuery
		assertThat(toJson(request)).contains("remainingMatchedMenus0");
	}

	@Test
	void build_treatsRegionAsShouldWhenOnlyRemainingIsShortNoise() {
		// "성수점" → region "성수" + remaining "점"(길이1 노이즈). 유효 잔여가 없어 지역 단독처럼 처리.
		SearchIntent intent = intent("성수점", List.of("성수"), List.of(), List.of("점"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).isEmpty();
		assertThat(bool.should()).hasSize(2); // region("성수") + rawQuery
		assertThat(bool.minimumShouldMatch()).isEqualTo("1");
		assertThat(toJson(request)).doesNotContain("remainingMatchedMenus");
	}

	@Test
	void build_usesRegionAsShouldWhenRegionOnly() {
		// 지역 단독("한남") → 거리 감쇠로 주변까지 포함하도록 should 유지
		SearchIntent intent = intent("한남", List.of("한남"), List.of());

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		BoolQuery bool = boolQuery(request);
		assertThat(bool.must()).isEmpty();
		assertThat(bool.should()).hasSize(2); // regionQuery("한남") + rawQuery
		assertThat(bool.minimumShouldMatch()).isEqualTo("1");
	}

	@Test
	void build_reflectsPopularityScoreWithFunctionScore() {
		SearchIntent intent = intent("소금빵", List.of(), List.of("소금빵"));

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		FunctionScoreQuery functionScore = request.query().functionScore();
		assertThat(functionScore.boostMode()).isEqualTo(FunctionBoostMode.Multiply);
		assertThat(functionScore.scoreMode()).isEqualTo(FunctionScoreMode.Sum);
		assertThat(toJson(request)).contains(
			"field_value_factor",
			"popularityScore",
			"log1p");
	}

	@Test
	void build_boostsNormalizedWholeStoreNameAbovePartialNoriMatches() {
		SearchIntent intent = intent("후와 후와", List.of(), List.of());

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		assertThat(toJson(request)).contains(
			"storeName.edge",
			"storeName.ngram",
			"후와후와",
			"80.0",
			"60.0");
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
	void build_doesNotRequestExplainByDefault() {
		SearchIntent intent = intent("뺑스톡", List.of(), List.of());

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE);

		assertThat(request.explain()).isNotEqualTo(Boolean.TRUE);
	}

	@Test
	void build_requestsExplainWhenExplainFlagTrue() {
		SearchIntent intent = intent("뺑스톡", List.of(), List.of());

		SearchRequest request = builder.build(intent, PageRequest.of(0, 15), null, null, SearchSort.RELEVANCE, true);

		assertThat(request.explain()).isTrue();
		assertThat(toJson(request)).contains("\"explain\":true");
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
		return intent(rawQuery, regionTokens, breadTokens, List.of());
	}

	private SearchIntent intent(String rawQuery, List<String> regionTokens, List<String> breadTokens,
			List<String> remainingTokens) {
		return new SearchIntent(rawQuery, rawQuery, List.of(rawQuery.split(" ")), regionTokens, breadTokens,
			remainingTokens);
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
